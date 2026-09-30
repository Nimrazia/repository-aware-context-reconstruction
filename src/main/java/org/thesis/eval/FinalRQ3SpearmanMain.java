package org.thesis.eval;

import org.apache.commons.math3.distribution.TDistribution;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FinalRQ3SpearmanMain {

    private static final List<String> LLMS =
            List.of("claude", "deepseek", "gemini", "gpt", "qwen");

    private FinalRQ3SpearmanMain() {}

    public static void main(String[] args) {
        try {
            Path project =
                    args.length == 0
                            ? Path.of(".")
                            : Path.of(args[0]);

            project = project.toAbsolutePath().normalize();

            Path lcomCsv =
                    project.resolve("input")
                            .resolve("lcom4_method_values.csv");

            Path f1Csv =
                    project.resolve("results")
                            .resolve("RQ2")
                            .resolve("FINAL_F1_ALL_500.csv");

            Path out =
                    project.resolve("results")
                            .resolve("RQ3")
                            .resolve("RQ3_JAVA_REPRODUCED");

            Files.createDirectories(out);

            require(lcomCsv, "LCOM4 input");
            require(f1Csv, "Final certified 500-score input");

            System.out.println("============================================================");
            System.out.println("FINAL RQ3 — LCOM4 vs DEPENDENCY F1");
            System.out.println("============================================================");
            System.out.println("LCOM4: " + lcomCsv);
            System.out.println("F1:    " + f1Csv);

            List<LcomRow> lcomRows = readLcom(lcomCsv);
            List<F1Row> f1Rows = readF1(f1Csv);

            if (lcomRows.size() != 100) {
                throw new IllegalStateException(
                        "Expected exactly 100 LCOM4 method rows, found "
                                + lcomRows.size());
            }

            if (f1Rows.size() != 500) {
                throw new IllegalStateException(
                        "Expected exactly 500 final F1 rows, found "
                                + f1Rows.size());
            }

            validateF1Universe(f1Rows);

            Map<String, LcomRow> lcomByMethod =
                    new LinkedHashMap<>();

            for (LcomRow r : lcomRows) {
                String key = r.methodKey();

                if (lcomByMethod.putIfAbsent(key, r) != null) {
                    throw new IllegalStateException(
                            "Duplicate LCOM4 method key: " + key);
                }
            }

            List<PairRow> pairs =
                    new ArrayList<>();

            for (F1Row f : f1Rows) {
                LcomRow l = lcomByMethod.get(f.methodKey());

                if (l == null) {
                    throw new IllegalStateException(
                            "No LCOM4 value for final F1 method "
                                    + f.methodKey()
                                    + " (" + f.function + ")");
                }

                pairs.add(new PairRow(
                        f.llm,
                        f.repo,
                        f.method,
                        f.globalMethod,
                        f.function,
                        l.declaringClass,
                        l.lcom4,
                        f.f1
                ));
            }

            if (pairs.size() != 500) {
                throw new IllegalStateException(
                        "Expected 500 LCOM4-F1 pairs, found " + pairs.size());
            }

            writePairs(out.resolve("LCOM4_F1_PAIRS_500.csv"), pairs);

            List<ResultRow> results =
                    new ArrayList<>();

            for (String llm : LLMS) {
                List<PairRow> x =
                        pairs.stream()
                                .filter(p -> p.llm.equals(llm))
                                .sorted(Comparator
                                        .comparingInt((PairRow p) -> p.repo)
                                        .thenComparingInt(p -> p.method))
                                .toList();

                if (x.size() != 100) {
                    throw new IllegalStateException(
                            llm + " has " + x.size()
                                    + " RQ3 pairs; expected 100.");
                }

                double[] lcom =
                        x.stream()
                                .mapToDouble(p -> p.lcom4)
                                .toArray();

                double[] f1 =
                        x.stream()
                                .mapToDouble(p -> p.f1)
                                .toArray();

                double rho = spearman(lcom, f1);
                double p = asymptoticTwoSidedP(rho, x.size());

                results.add(new ResultRow(
                        llm,
                        x.size(),
                        rho,
                        p,
                        direction(rho),
                        strength(rho),
                        p < 0.05
                ));
            }

            writeResults(out.resolve("SPEARMAN_BY_LLM.csv"), results);
            writeSummary(out.resolve("RQ3_FINAL_SUMMARY.txt"), results, pairs);

            System.out.println();
            System.out.println("============================================================");
            System.out.println("FINAL RQ3 COMPLETE");
            System.out.println("============================================================");

            for (ResultRow r : results) {
                System.out.printf(
                        Locale.ROOT,
                        "%-8s n=%d  rho=% .6f  p=%.6g  %s, %s, significant=%s%n",
                        r.llm,
                        r.n,
                        r.rho,
                        r.pValue,
                        r.direction,
                        r.strength,
                        r.significant ? "YES" : "NO"
                );
            }

            System.out.println();
            System.out.println("Results: " + out.toAbsolutePath());

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static List<LcomRow> readLcom(Path file) throws Exception {
        CsvTable table = CsvTable.read(file);

        String repoCol = firstColumn(table,
                "repo", "repository_index", "repo_index", "repo_number", "repository");

        String methodCol = firstColumn(table,
                "method", "method_number", "method_index", "repo_method");

        String globalCol = optionalColumn(table,
                "global_method", "global_method_number", "globalmethod", "global_method_id", "method_id");

        String functionCol = optionalColumn(table,
                "function", "function_name", "target_function", "method_name");

        String classCol = optionalColumn(table,
                "declaring_class", "declaring_type", "class", "class_name", "declaringclass");

        String lcomCol = firstColumn(table,
                "repository_lcom4", "lcom4", "lcom_4", "lcom", "lcom4_value");

        List<LcomRow> rows = new ArrayList<>();

        for (Map<String, String> r : table.rows) {
            int repo = integer(r.get(repoCol));
            int method = integer(r.get(methodCol));

            if (repo <= 0 || method <= 0) {
                throw new IllegalStateException(
                        "Invalid LCOM4 repo/method row: " + r);
            }

            double lcom4 = decimal(r.get(lcomCol));

            rows.add(new LcomRow(
                    repo,
                    method,
                    globalCol == null ? 0 : integer(r.get(globalCol)),
                    functionCol == null ? "" : text(r.get(functionCol)),
                    classCol == null ? "" : text(r.get(classCol)),
                    lcom4
            ));
        }

        rows.sort(Comparator
                .comparingInt((LcomRow r) -> r.repo)
                .thenComparingInt(r -> r.method));

        return rows;
    }

    private static List<F1Row> readF1(Path file) throws Exception {
        CsvTable table = CsvTable.read(file);

        String llmCol = firstColumn(table, "llm");
        String repoCol = firstColumn(table, "repo");
        String methodCol = firstColumn(table, "method");
        String globalCol = optionalColumn(table, "global_method");
        String functionCol = optionalColumn(table, "function");
        String f1Col = firstColumn(table, "f1");

        List<F1Row> rows = new ArrayList<>();

        for (Map<String, String> r : table.rows) {
            rows.add(new F1Row(
                    lower(r.get(llmCol)),
                    integer(r.get(repoCol)),
                    integer(r.get(methodCol)),
                    globalCol == null ? 0 : integer(r.get(globalCol)),
                    functionCol == null ? "" : text(r.get(functionCol)),
                    decimal(r.get(f1Col))
            ));
        }

        rows.sort(Comparator
                .comparing((F1Row r) -> r.llm)
                .thenComparingInt(r -> r.repo)
                .thenComparingInt(r -> r.method));

        return rows;
    }

    private static void validateF1Universe(List<F1Row> rows) {
        Set<String> seen = new LinkedHashSet<>();

        for (F1Row r : rows) {
            if (!LLMS.contains(r.llm)) {
                throw new IllegalStateException(
                        "Unexpected LLM in final F1: " + r.llm);
            }

            String key = r.llm + "|" + r.methodKey();

            if (!seen.add(key)) {
                throw new IllegalStateException(
                        "Duplicate final method-LLM row: " + key);
            }
        }

        for (String llm : LLMS) {
            long n = rows.stream()
                    .filter(r -> r.llm.equals(llm))
                    .count();

            if (n != 100) {
                throw new IllegalStateException(
                        llm + " final F1 rows=" + n + "; expected 100.");
            }
        }
    }

    private static double spearman(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) {
            throw new IllegalArgumentException("Spearman arrays differ or too small.");
        }

        double[] rx = averageTieRanks(x);
        double[] ry = averageTieRanks(y);

        return pearson(rx, ry);
    }

    private static double[] averageTieRanks(double[] values) {
        int n = values.length;

        List<IndexedValue> sorted = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            sorted.add(new IndexedValue(i, values[i]));
        }

        sorted.sort(Comparator
                .comparingDouble((IndexedValue v) -> v.value)
                .thenComparingInt(v -> v.index));

        double[] ranks = new double[n];

        int i = 0;

        while (i < n) {
            int j = i + 1;

            while (j < n
                    && Double.compare(
                            sorted.get(j).value,
                            sorted.get(i).value) == 0) {
                j++;
            }

            double averageRank =
                    ((i + 1) + j) / 2.0;

            for (int k = i; k < j; k++) {
                ranks[sorted.get(k).index] = averageRank;
            }

            i = j;
        }

        return ranks;
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;

        double mx = 0.0;
        double my = 0.0;

        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }

        mx /= n;
        my /= n;

        double numerator = 0.0;
        double sx = 0.0;
        double sy = 0.0;

        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;

            numerator += dx * dy;
            sx += dx * dx;
            sy += dy * dy;
        }

        double denominator = Math.sqrt(sx * sy);

        if (denominator == 0.0) {
            return 0.0;
        }

        return numerator / denominator;
    }

    private static double asymptoticTwoSidedP(double rho, int n) {
        if (n < 3 || !Double.isFinite(rho)) return Double.NaN;
        double abs = Math.abs(rho);
        if (abs >= 1.0) return 0.0;
        double t = abs * Math.sqrt((n - 2.0) / (1.0 - rho * rho));
        TDistribution dist = new TDistribution(n - 2.0);
        return 2.0 * (1.0 - dist.cumulativeProbability(t));
    }

    private static String direction(double rho) {
        if (rho > 0.0) return "positive";
        if (rho < 0.0) return "negative";
        return "none";
    }

    private static String strength(double rho) {
        double a = Math.abs(rho);

        if (a < 0.10) return "negligible";
        if (a < 0.30) return "weak";
        if (a < 0.50) return "moderate";
        if (a < 0.70) return "strong";

        return "very strong";
    }

    private static void writePairs(
            Path file,
            List<PairRow> pairs) throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {

            w.write(
                    "llm,repo,method,global_method,function,"
                            + "declaring_class,lcom4,final_dependency_f1");
            w.newLine();

            pairs.stream()
                    .sorted(Comparator
                            .comparing((PairRow p) -> p.llm)
                            .thenComparingInt(p -> p.repo)
                            .thenComparingInt(p -> p.method))
                    .forEach(p -> {
                        try {
                            w.write(csv(
                                    p.llm,
                                    p.repo,
                                    p.method,
                                    p.globalMethod,
                                    p.function,
                                    p.declaringClass,
                                    fmt(p.lcom4),
                                    fmt(p.f1)
                            ));
                            w.newLine();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private static void writeResults(
            Path file,
            List<ResultRow> rows) throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {

            w.write(
                    "llm,n,spearman_rho,p_value,direction,strength,"
                            + "significant_at_0_05");
            w.newLine();

            for (ResultRow r : rows) {
                w.write(csv(
                        r.llm,
                        r.n,
                        fmt(r.rho),
                        fmtP(r.pValue),
                        r.direction,
                        r.strength,
                        r.significant
                ));
                w.newLine();
            }
        }
    }

    private static void writeSummary(
            Path file,
            List<ResultRow> results,
            List<PairRow> pairs) throws Exception {

        StringBuilder b = new StringBuilder();

        b.append("FINAL RQ3 — LCOM4 AND DEPENDENCY-RECONSTRUCTION F1\n");
        b.append("===================================================\n\n");

        b.append("Repository methods: 100\n");
        b.append("LLMs: 5\n");
        b.append("Method-LLM pairs: 500\n\n");

        b.append("INPUTS\n");
        b.append("------\n");
        b.append("LCOM4: input/lcom4_method_values.csv\n");
        b.append("F1: results/RQ2/FINAL_F1_ALL_500.csv\n\n");

        b.append("METHOD\n");
        b.append("------\n");
        b.append("Each target method is paired with the LCOM4 value of its original\n");
        b.append("declaring repository class. For each LLM, the 100 LCOM4 values are\n");
        b.append("correlated with that LLM's 100 final certified method-level dependency\n");
        b.append("F1 scores. Average ranks are assigned to ties and Spearman's rho is\n");
        b.append("computed as the Pearson correlation of the rank vectors.\n\n");

        b.append("RESULTS\n");
        b.append("-------\n");

        for (ResultRow r : results) {
            b.append(String.format(
                    Locale.ROOT,
                    "%-8s n=%3d  rho=% .6f  p=%s  %s %s correlation; p<0.05=%s%n",
                    r.llm,
                    r.n,
                    r.rho,
                    fmtP(r.pValue),
                    r.strength,
                    r.direction,
                    r.significant ? "YES" : "NO"
            ));
        }

        b.append("\nINTERPRETATION THRESHOLDS\n");
        b.append("-------------------------\n");
        b.append("|rho| < 0.10 : negligible\n");
        b.append("|rho| < 0.30 : weak\n");
        b.append("|rho| < 0.50 : moderate\n");
        b.append("|rho| < 0.70 : strong\n");
        b.append("otherwise    : very strong\n");
        b.append("Statistical significance: p < 0.05\n\n");

        b.append("IMPORTANT\n");
        b.append("---------\n");
        b.append("These correlations use the FINAL certified dependency F1 dataset.\n");
        b.append("Older Spearman results calculated from earlier F1 scores must not be\n");
        b.append("reported as the final RQ3 result.\n");

        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    }

    private static String firstColumn(CsvTable table, String... aliases) {
        String x = optionalColumn(table, aliases);

        if (x == null) {
            throw new IllegalStateException(
                    "Required column not found. Tried "
                            + List.of(aliases)
                            + ". Actual columns="
                            + table.columns);
        }

        return x;
    }

    private static String optionalColumn(CsvTable table, String... aliases) {
        for (String a : aliases) {
            for (String actual : table.columns) {
                if (actual.equalsIgnoreCase(a)) {
                    return actual;
                }
            }
        }

        return null;
    }

    private static final class CsvTable {
        final List<String> columns;
        final List<Map<String, String>> rows;

        CsvTable(
                List<String> columns,
                List<Map<String, String>> rows) {
            this.columns = columns;
            this.rows = rows;
        }

        static CsvTable read(Path file) throws Exception {
            List<Map<String, String>> rows = new ArrayList<>();

            try (BufferedReader reader =
                         Files.newBufferedReader(file, StandardCharsets.UTF_8)) {

                String headerLine = reader.readLine();

                if (headerLine == null) {
                    throw new IllegalStateException("CSV is empty: " + file);
                }

                List<String> columns = parseCsvLine(headerLine);

                if (!columns.isEmpty()) {
                    columns.set(
                            0,
                            columns.get(0).replace("\uFEFF", "").trim()
                    );
                }

                for (int i = 1; i < columns.size(); i++) {
                    columns.set(i, columns.get(i).trim());
                }

                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;

                    List<String> values = parseCsvLine(line);

                    Map<String, String> row = new LinkedHashMap<>();

                    for (int i = 0; i < columns.size(); i++) {
                        row.put(
                                columns.get(i),
                                i < values.size() ? values.get(i) : ""
                        );
                    }

                    rows.add(row);
                }

                return new CsvTable(columns, rows);
            }
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (quoted
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        values.add(current.toString());

        return values;
    }

    private static String csv(Object... values) {
        StringBuilder b = new StringBuilder();

        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(',');

            String s =
                    values[i] == null ? "" : String.valueOf(values[i]);

            b.append('"')
                    .append(s.replace("\"", "\"\""))
                    .append('"');
        }

        return b.toString();
    }

    private static void require(Path file, String label) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    label + " not found:\n" + file.toAbsolutePath());
        }
    }

    private static String text(String x) {
        return x == null ? "" : x.trim();
    }

    private static String lower(String x) {
        return text(x).toLowerCase(Locale.ROOT);
    }

    private static int integer(String x) {
        try {
            return Integer.parseInt(text(x));
        } catch (Exception e) {
            return 0;
        }
    }

    private static double decimal(String x) {
        try {
            return Double.parseDouble(text(x));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Invalid numeric value: '" + x + "'");
        }
    }

    private static String fmt(double x) {
        return String.format(Locale.ROOT, "%.6f", x);
    }

    private static String fmtP(double x) {
        if (x == 0.0) return "0.000000";
        if (x < 0.000001) {
            return String.format(Locale.ROOT, "%.6e", x);
        }
        return String.format(Locale.ROOT, "%.6f", x);
    }

    private record IndexedValue(int index, double value) {}

    private record LcomRow(
            int repo,
            int method,
            int globalMethod,
            String function,
            String declaringClass,
            double lcom4) {

        String methodKey() {
            return repo + ":" + method;
        }
    }

    private record F1Row(
            String llm,
            int repo,
            int method,
            int globalMethod,
            String function,
            double f1) {

        String methodKey() {
            return repo + ":" + method;
        }
    }

    private record PairRow(
            String llm,
            int repo,
            int method,
            int globalMethod,
            String function,
            String declaringClass,
            double lcom4,
            double f1) {}

    private record ResultRow(
            String llm,
            int n,
            double rho,
            double pValue,
            String direction,
            String strength,
            boolean significant) {}
}
