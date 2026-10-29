// Copyright (c) 2025-2026 Hugo He
// Ported from ppt-master (MIT) commit cc535363b8cf6e3abd0543d578d32b4f4cc3cf98.

package domi.argenticpptmaster.engine.file.svgquality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal freeform path/points parsers for quality blocking errors. */
final class SvgQualityPath {

    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?");
    private static final Set<Character> COMMANDS =
            Set.of('M', 'L', 'H', 'V', 'C', 'S', 'Q', 'T', 'A', 'Z');

    private SvgQualityPath() {}

    static void parseSvgPath(String d) {
        if (d == null || d.isBlank()) {
            throw new IllegalArgumentException("path data must not be empty");
        }
        String text = d.strip();
        int i = 0;
        Character command = null;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == ',') {
                i++;
                continue;
            }
            if (COMMANDS.contains(Character.toUpperCase(ch))) {
                command = Character.toUpperCase(ch);
                i++;
                if (command == 'Z') {
                    continue;
                }
            } else if (command == null) {
                throw new IllegalArgumentException("path data must start with a command");
            }
            int needed = argsFor(command);
            if (needed == 0) {
                continue;
            }
            List<Double> args = new ArrayList<>(needed);
            while (args.size() < needed) {
                while (i < text.length()
                        && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
                    i++;
                }
                if (i >= text.length()) {
                    throw new IllegalArgumentException(
                            "path command " + command + " requires " + needed + " arguments");
                }
                if (COMMANDS.contains(Character.toUpperCase(text.charAt(i)))) {
                    throw new IllegalArgumentException(
                            "path command " + command + " requires " + needed + " arguments");
                }
                Matcher match = NUMBER.matcher(text);
                if (!match.find(i) || match.start() != i) {
                    throw new IllegalArgumentException(
                            "invalid path number near '" + text.substring(i, Math.min(i + 12, text.length())) + "'");
                }
                double value = Double.parseDouble(match.group());
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("path numbers must be finite");
                }
                args.add(value);
                i = match.end();
            }
            if (command == 'A' && args.size() >= 7) {
                // flags already parsed as numbers; accept 0/1 loosely
            }
            // implicit repeat of same command for leftover numbers is not required for quality gate
        }
    }

    static List<double[]> parseSvgPoints(String points, int minPoints) {
        if (points == null || points.isBlank()) {
            throw new IllegalArgumentException("points must not be empty");
        }
        Matcher matcher = NUMBER.matcher(points);
        List<Double> tokens = new ArrayList<>();
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group());
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("points numbers must be finite");
            }
            tokens.add(value);
        }
        if (tokens.size() % 2 != 0) {
            throw new IllegalArgumentException(
                    "points has " + tokens.size() + " numeric value(s); expected coordinate pairs");
        }
        int pointCount = tokens.size() / 2;
        if (pointCount < minPoints) {
            throw new IllegalArgumentException(
                    "points requires at least " + minPoints + " coordinate pair(s); found " + pointCount);
        }
        List<double[]> out = new ArrayList<>(pointCount);
        for (int i = 0; i < tokens.size(); i += 2) {
            out.add(new double[] {tokens.get(i), tokens.get(i + 1)});
        }
        return out;
    }

    static void parseTransformOperations(String transform) {
        if (transform == null || transform.isBlank()) {
            throw new IllegalArgumentException("SVG transform must not be empty");
        }
        Pattern op = Pattern.compile(
                "(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = op.matcher(transform);
        int found = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() > end) {
                String gap = transform.substring(end, matcher.start()).strip();
                if (!gap.isEmpty()) {
                    throw new IllegalArgumentException("unsupported transform fragment '" + gap + "'");
                }
            }
            found++;
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            List<Double> args = new ArrayList<>();
            Matcher num = NUMBER.matcher(matcher.group(2));
            while (num.find()) {
                double value = Double.parseDouble(num.group());
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("transform numbers must be finite");
                }
                args.add(value);
            }
            validateTransformArity(name, args.size());
            end = matcher.end();
        }
        if (found == 0) {
            throw new IllegalArgumentException("SVG transform must not be empty");
        }
        String trailing = transform.substring(end).strip();
        if (!trailing.isEmpty()) {
            throw new IllegalArgumentException("unsupported transform fragment '" + trailing + "'");
        }
    }

    private static void validateTransformArity(String name, int count) {
        boolean ok = switch (name) {
            case "matrix" -> count == 6;
            case "translate", "scale" -> count == 1 || count == 2;
            case "rotate" -> count == 1 || count == 3;
            case "skewx", "skewy" -> count == 1;
            default -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("transform " + name + " has invalid argument count " + count);
        }
    }

    private static int argsFor(char command) {
        return switch (command) {
            case 'Z' -> 0;
            case 'H', 'V' -> 1;
            case 'M', 'L', 'T' -> 2;
            case 'S', 'Q' -> 4;
            case 'C' -> 6;
            case 'A' -> 7;
            default -> throw new IllegalArgumentException("unsupported path command " + command);
        };
    }
}
