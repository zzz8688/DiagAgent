package io.github.zzz8688.diagagent.store;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class Bm25Scorer {

    @Value("${knowledge.rag.bm25.k1:1.5}")
    private double k1;

    @Value("${knowledge.rag.bm25.b:0.75}")
    private double b;

    public double score(String query, TextSegment segment, List<TextSegment> corpus) {
        if (segment == null || corpus == null || corpus.isEmpty()) {
            return 0D;
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return 0D;
        }

        List<String> docTerms = tokenize(segment.text());
        if (docTerms.isEmpty()) {
            return 0D;
        }

        Map<String, Integer> docTermFreq = termFrequency(docTerms);
        double avgDocLength = corpus.stream()
                .map(TextSegment::text)
                .map(this::tokenize)
                .mapToInt(List::size)
                .average()
                .orElse(1D);

        double documentLength = docTerms.size();
        double score = 0D;
        for (String term : new LinkedHashSet<>(queryTerms)) {
            int tf = docTermFreq.getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequency(term, corpus);
            double idf = Math.log(1D + (corpus.size() - df + 0.5D) / (df + 0.5D));
            double numerator = tf * (k1 + 1D);
            double denominator = tf + k1 * (1D - b + b * (documentLength / Math.max(avgDocLength, 1D)));
            score += idf * (numerator / denominator);
        }
        return score;
    }

    public List<String> tokenize(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return List.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (containsChinese(token)) {
                if (token.length() >= 2) {
                    tokens.add(token);
                }
                for (int index = 0; index < token.length() - 1; index++) {
                    tokens.add(token.substring(index, index + 2));
                }
                if (token.length() == 1) {
                    tokens.add(token);
                }
            } else if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return new ArrayList<>(tokens);
    }

    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    public boolean containsChinese(String text) {
        for (char ch : text.toCharArray()) {
            if (ch >= 0x4E00 && ch <= 0x9FA5) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String token : tokens) {
            frequency.merge(token, 1, Integer::sum);
        }
        return frequency;
    }

    private int documentFrequency(String term, List<TextSegment> corpus) {
        int count = 0;
        for (TextSegment segment : corpus) {
            List<String> tokens = tokenize(segment.text());
            if (tokens.contains(term)) {
                count++;
            }
        }
        return Math.max(count, 1);
    }
}
