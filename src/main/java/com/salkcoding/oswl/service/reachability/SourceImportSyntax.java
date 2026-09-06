package com.salkcoding.oswl.service.reachability;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Lexical filtering prevents examples in comments/strings from becoming reference evidence.
 * This is a static syntax subset, never a runtime reachability proof. */
final class SourceImportSyntax {
    private SourceImportSyntax() {}
    private record Token(String text, boolean literal) {}

    static String pythonCode(String text) {
        StringBuilder code = new StringBuilder(text);
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') code.setCharAt(i++, ' ');
            } else if (c == '\'' || c == '"') {
                boolean triple = i + 2 < text.length() && text.charAt(i+1) == c && text.charAt(i+2) == c;
                int width = triple ? 3 : 1;
                int start = i; i += width;
                while (i < text.length()) {
                    if (text.charAt(i) == '\\') { i = Math.min(i + 2, text.length()); continue; }
                    if (text.charAt(i) == c && (!triple || i+2 < text.length() && text.charAt(i+1) == c && text.charAt(i+2) == c)) {
                        i += width; break;
                    }
                    i++;
                }
                for (int j = start; j < i; j++) if (code.charAt(j) != '\n') code.setCharAt(j, ' ');
            } else i++;
        }
        return code.toString();
    }

    static Set<String> javascriptSpecifiers(String text) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i+1 < text.length() && text.charAt(i+1) == '/') {
                i += 2; while (i < text.length() && text.charAt(i) != '\n') i++; continue;
            }
            if (c == '/' && i+1 < text.length() && text.charAt(i+1) == '*') {
                int end = text.indexOf("*/", i+2); i = end < 0 ? text.length() : end+2; continue;
            }
            if (c == '/') {
                // Treat a slash-delimited span conservatively as a regex literal. Ambiguous
                // division expressions may lose evidence, but regex examples must not add it.
                int end = i + 1;
                boolean inClass = false;
                for (; end < text.length() && text.charAt(end) != '\n'; end++) {
                    char next = text.charAt(end);
                    if (next == '\\') { end++; continue; }
                    if (next == '[') inClass = true;
                    if (next == ']') inClass = false;
                    if (next == '/' && !inClass) break;
                }
                if (end < text.length() && text.charAt(end) == '/') {
                    tokens.add(new Token("", true)); i = end + 1; continue;
                }
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; StringBuilder value = new StringBuilder(); i++;
                boolean escaped = false;
                while (i < text.length() && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\' && i+1 < text.length()) { escaped = true; i += 2; }
                    else value.append(text.charAt(i++));
                }
                boolean closed = i < text.length(); i = Math.min(i+1, text.length());
                // Escaped module names and template expressions need more analysis.
                tokens.add(new Token(closed && !escaped && quote != '`' ? value.toString() : "", true));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i++; while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                tokens.add(new Token(text.substring(start, i), false));
            } else { tokens.add(new Token(String.valueOf(c), false)); i++; }
        }
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.literal() || i > 0 && tokens.get(i-1).text().equals(".")) continue;
            if (token.text().equals("require") || token.text().equals("import")) {
                if (i+3 < tokens.size() && tokens.get(i+1).text().equals("(") && tokens.get(i+2).literal()
                        && tokens.get(i+3).text().equals(")")) found.add(tokens.get(i+2).text());
                if (token.text().equals("import") && i+1 < tokens.size() && tokens.get(i+1).literal())
                    found.add(tokens.get(i+1).text());
            }
            if (token.text().equals("import") || token.text().equals("export")) {
                for (int j = i+1; j+1 < tokens.size() && j < i+100; j++) {
                    String part = tokens.get(j).text();
                    if (part.equals(";") || part.equals("import") || part.equals("export")) break;
                    if (!tokens.get(j).literal() && part.equals("from") && tokens.get(j+1).literal()) {
                        found.add(tokens.get(j+1).text()); break;
                    }
                }
            }
        }
        found.remove("");
        return found;
    }
}
