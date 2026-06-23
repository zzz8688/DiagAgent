import { useEffect, useState } from 'react'
import { markdownToHtml } from '../utils/markdownToHtml'

interface MarkdownMessageProps {
  markdown: string
  streaming?: boolean
}

export function MarkdownMessage({ markdown, streaming = false }: MarkdownMessageProps) {
  const [html, setHtml] = useState('')

  useEffect(() => {
    let cancelled = false

    if (!markdown.trim()) {
      setHtml('')
      return () => {
        cancelled = true
      }
    }

    markdownToHtml(markdown, { enableSyntaxFix: true }).then((value) => {
      if (!cancelled) {
        setHtml(value)
      }
    })

    return () => {
      cancelled = true
    }
  }, [markdown, streaming])

  return (
    <div className={`markdown-stream${streaming ? ' streaming' : ''}`}>
      {html ? <div className="markdown-body markdown-section" dangerouslySetInnerHTML={{ __html: html }} /> : null}
    </div>
  )
}
