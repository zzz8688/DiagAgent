import { unified } from 'unified'
import remarkParse from 'remark-parse'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import remarkRehype from 'remark-rehype'
import rehypeKatex from 'rehype-katex'
import rehypeHighlight from 'rehype-highlight'
import rehypeStringify from 'rehype-stringify'
import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github.css'

interface MarkdownOptions {
  enableSyntaxFix?: boolean
}

const STREAMING_SECTION_TITLES = [
  '当前判断',
  '已确认事实',
  '收口判断',
  '排查步骤',
  '诊断结论',
  '最终结论',
  '证据依据',
  '根因分析',
  '根因判断',
  '根因判断待确认',
  '下一步建议',
  '风险评估',
  '建议',
  '确认',
  '系统健康状态',
] as const

const orderedStreamingTitles = [...STREAMING_SECTION_TITLES].sort((left, right) => right.length - left.length)

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function buildSectionTitleRegex() {
  return new RegExp(`#{0,6}\\s*(${orderedStreamingTitles.map(escapeRegExp).join('|')})`, 'g')
}

function normalizeInlineSectionBoundaries(text: string): string {
  const titlesPattern = orderedStreamingTitles.map(escapeRegExp).join('|')
  return text
    .replace(new RegExp(`([^\\n])(#{1,6}\\s*(?:${titlesPattern}))`, 'g'), '$1\n\n$2')
    .replace(new RegExp(`(#{1,6}\\s*(?:${titlesPattern}))(?!\\n|$|\\s*#{1,6}\\s)`, 'g'), '$1\n')
    .replace(/(#{1,6}\s*(?:收口判断|建议|排查步骤))([*-]\s+)/g, '$1\n$2')
}

export function normalizeStreamingMarkdown(text: string): string {
  const normalized = normalizeInlineSectionBoundaries(text
    .replace(/\r\n?/g, '\n')
    .replace(/\u00a0/g, ' '))

  const titleRegex = buildSectionTitleRegex()
  const matches = Array.from(normalized.matchAll(titleRegex))
    .filter((match) => {
      const start = match.index ?? 0
      if (start === 0) {
        return true
      }
      const previous = normalized[start - 1]
      return /[\s\n#>[(\u3000]/.test(previous)
    })
    .map((match) => ({
      title: match[1],
      index: match.index ?? 0,
      raw: match[0],
    }))

  if (matches.length === 0) {
    return applyStreamingLineBreakFixes(normalized)
      .replace(/([^\n])(#{1,6}\s)/g, '$1\n\n$2')
      .replace(/([^\n])(-\s)/g, '$1\n\n$2')
      .replace(/\n{3,}/g, '\n\n')
      .trim()
  }

  const sections: string[] = []
  const firstIndex = matches[0].index
  const prefix = normalized.slice(0, firstIndex).trim()
  if (prefix) {
    sections.push(prefix)
  }

  matches.forEach((match, index) => {
    const nextIndex = matches[index + 1]?.index ?? normalized.length
    const rawSection = normalized.slice(match.index, nextIndex).trim()
    let rest = rawSection.replace(/^#{0,6}\s*/, '')
    if (rest.startsWith(match.title)) {
      rest = rest.slice(match.title.length)
    }
    rest = rest.replace(/^[\s:：]*/, '')
    sections.push(`## ${match.title}${rest ? `\n${rest.trimStart()}` : ''}`)
  })

  return applyStreamingLineBreakFixes(sections.join('\n\n'))
    .replace(/([^\n])\n(#{1,6}\s)/g, '$1\n\n$2')
    .replace(/([^\n])\n(-\s)/g, '$1\n\n$2')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function applyStreamingLineBreakFixes(text: string): string {
  return text
    .replace(/^(#{1,6}\s*(?:当前判断|已确认事实|收口判断|排查步骤|建议|根因判断|根因分析))([^\n#-][^\n]*)$/gm, '$1\n$2')
    .replace(/^(#{1,6}\s*已确认事实)(\d+\.\s*)/gm, '$1\n$2')
    .replace(/^(#{1,6}\s*收口判断)([-*]\s*)/gm, '$1\n$2')
    .replace(/([^\n])(\d+\.\s+)/g, '$1\n$2')
}

export function repairMarkdown(text: string): string {
  let fixed = normalizeStreamingMarkdown(text)

  if (((fixed.match(/```/g) ?? []).length) % 2 !== 0) {
    fixed += '\n```'
  }
  if (((fixed.match(/\$\$/g) ?? []).length) % 2 !== 0) {
    fixed += '\n$$'
  }
  if (((fixed.match(/(?<!\\)`/g) ?? []).length) % 2 !== 0) {
    fixed += '`'
  }
  if (((fixed.match(/(?<!\\)\$/g) ?? []).length) % 2 !== 0) {
    fixed += '$'
  }
  return fixed
}

export async function markdownToHtml(markdown: string, options: MarkdownOptions = {}): Promise<string> {
  const source = options.enableSyntaxFix ? repairMarkdown(markdown) : markdown
  const file = await unified()
    .use(remarkParse)
    .use(remarkGfm)
    .use(remarkMath)
    .use(remarkRehype)
    .use(rehypeKatex)
    .use(rehypeHighlight, { detect: true, ignoreMissing: true })
    .use(rehypeStringify)
    .process(source)

  return String(file)
}
