package org.thesis.eval;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.Parameter;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class FinalRQ1JacocoMain {

    private static final List<String> LLMS =
            List.of("claude", "deepseek", "gemini", "gpt", "qwen");

    private static final String JACOCO_VERSION = "0.8.14";

    private static final long COMPILE_TIMEOUT_SECONDS = 120;
    private static final long EXECUTE_TIMEOUT_SECONDS = 60;
    private static final long REPORT_TIMEOUT_SECONDS = 60;

    private FinalRQ1JacocoMain() {}

    public static void main(String[] args) {
        try {
            Path project =
                    args.length == 0
                            ? Path.of(".")
                            : Path.of(args[0]);

            project = project.toAbsolutePath().normalize();

            Path finalF1 =
                    project.resolve("results")
                            .resolve("RQ2")
                            .resolve("FINAL_F1_ALL_500.csv");

            Path methodsRoot =
                    project.resolve("input")
                            .resolve("methods");

            Path output =
                    project.resolve("results")
                            .resolve("RQ1");

            Path detailRoot =
                    output.resolve("detailed");

            Path workRoot =
                    output.resolve("_work");

            Files.createDirectories(output);
            Files.createDirectories(detailRoot);
            Files.createDirectories(workRoot);

            require(finalF1, "Final certified F1 CSV");

            if (!Files.isDirectory(methodsRoot)) {
                throw new IllegalStateException(
                        "Generated methods root not found:\n"
                                + methodsRoot.toAbsolutePath());
            }

            Path javaExe = javaExecutable("java");
            Path javacExe = javaExecutable("javac");

            Path agentJar = locateJacocoAgent();
            Path cliJar = locateJacocoCli();

            System.out.println("============================================================");
            System.out.println("FINAL RQ1 — TARGET-METHOD JACOCO COVERAGE");
            System.out.println("============================================================");
            System.out.println("Java:        " + javaExe);
            System.out.println("javac:       " + javacExe);
            System.out.println("JaCoCo agent:" + agentJar);
            System.out.println("JaCoCo CLI:  " + cliJar);
            System.out.println();

            List<FinalObservation> observations =
                    readFinalObservations(finalF1);

            if (observations.size() != 500) {
                throw new IllegalStateException(
                        "Expected 500 final observations; found "
                                + observations.size());
            }

            validateObservationUniverse(observations);

            System.out.println("ALL-500 FINAL MODE: " + observations.size() + " observations");

            Map<String, Path> indexedFiles =
                    indexGeneratedFiles(methodsRoot);

            List<CoverageRow> rows = new ArrayList<>();

            int index = 0;

            for (FinalObservation obs : observations) {
                index++;

                System.out.printf(
                        Locale.ROOT,
                        "[JACOCO %d/%d] %s repo%d method_%d %s%n",
                        index,
                        observations.size(),
                        obs.llm,
                        obs.repo,
                        obs.method,
                        obs.function);

                CoverageRow result =
                        evaluateOne(
                                project,
                                workRoot,
                                detailRoot,
                                obs,
                                indexedFiles,
                                javaExe,
                                javacExe,
                                agentJar,
                                cliJar
                        );

                rows.add(result);
            }

            validateCoverageUniverse(rows);

            writeAll500(
                    output.resolve("TARGET_METHOD_COVERAGE_ALL_500.csv"),
                    rows);

            writeLlmSummary(
                    output.resolve("TARGET_METHOD_COVERAGE_BY_LLM.csv"),
                    rows);

            writeSummary(
                    output.resolve("RQ1_JACOCO_SUMMARY.txt"),
                    rows);

            deleteRecursively(workRoot);

            System.out.println();
            System.out.println("============================================================");
            System.out.println("FINAL RQ1 JACOCO COMPLETE");
            System.out.println("============================================================");

            printConsoleSummary(rows);

            System.out.println();
            System.out.println("Results: " + output.toAbsolutePath());

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static CoverageRow evaluateOne(
            Path project,
            Path workRoot,
            Path detailRoot,
            FinalObservation obs,
            Map<String, Path> fileIndex,
            Path javaExe,
            Path javacExe,
            Path agentJar,
            Path cliJar) {

        CoverageRow r = new CoverageRow(obs);

        try {
            if (obs.file.isBlank()
                    || obs.file.equalsIgnoreCase("<MISSING>")) {
                r.status = "MISSING_RECONSTRUCTION";
                writeDetail(detailRoot, r);
                return r;
            }

            Path source =
                    findGeneratedFile(
                            obs,
                            fileIndex);

            if (source == null) {
                r.status = "SOURCE_FILE_NOT_FOUND";
                r.diagnostic =
                        "Final selected generated file not found under input/methods.";
                writeDetail(detailRoot, r);
                return r;
            }

            r.sourcePath = source.toString();

            TargetInfo target =
                    resolveTargetInfo(
                            source,
                            obs.function);

            if (target == null) {
                r.status = "TARGET_SOURCE_NOT_RESOLVED";
                r.diagnostic =
                        "Could not uniquely resolve target method in generated source.";
                writeDetail(detailRoot, r);
                return r;
            }

            r.targetClass = target.binaryClassName;
            r.targetMethod = target.methodName;
            r.targetStartLine = target.startLine;

            MainInfo main =
                    resolveMainInfo(source);

            if (main == null) {
                r.status = "MAIN_NOT_FOUND";
                r.diagnostic =
                        "No executable public/static main(String[]) method resolved.";
                writeDetail(detailRoot, r);
                return r;
            }

            r.mainClass = main.binaryClassName;

            Path work =
                    workRoot.resolve(
                            safe(obs.llm)
                                    + "_repo" + obs.repo
                                    + "_method" + obs.method);

            deleteRecursively(work);
            Files.createDirectories(work);

            Path classes = work.resolve("classes");
            Files.createDirectories(classes);

            Path compileLog = work.resolve("compile.log");

            List<String> compileCmd =
                    List.of(
                            javacExe.toString(),
                            "-g",
                            "-encoding",
                            "UTF-8",
                            "-d",
                            classes.toString(),
                            source.toString()
                    );

            ProcessResult compile =
                    runProcess(
                            compileCmd,
                            project,
                            compileLog,
                            COMPILE_TIMEOUT_SECONDS);

            r.compileExit = compile.exitCode;
            r.compileMs = compile.durationMs;

            if (compile.timedOut) {
                r.compileStatus = "TIMEOUT";
                r.status = "COMPILE_TIMEOUT";
                r.diagnostic = readSmall(compileLog);
                writeDetail(detailRoot, r);
                return r;
            }

            if (compile.exitCode != 0) {
                r.compileStatus = "FAIL";
                r.status = "COMPILE_FAIL";
                r.diagnostic = readSmall(compileLog);
                writeDetail(detailRoot, r);
                return r;
            }

            r.compileStatus = "PASS";

            Path execFile = work.resolve("jacoco.exec");
            Path runLog = work.resolve("run.log");

            String agentArg =
                    "-javaagent:"
                            + agentJar
                            + "=destfile="
                            + execFile
                            + ",append=false,output=file";

            List<String> runCmd =
                    List.of(
                            javaExe.toString(),
                            agentArg,
                            "-cp",
                            classes.toString(),
                            main.binaryClassName
                    );

            ProcessResult execute =
                    runProcess(
                            runCmd,
                            project,
                            runLog,
                            EXECUTE_TIMEOUT_SECONDS);

            r.executionExit = execute.exitCode;
            r.executionMs = execute.durationMs;

            if (execute.timedOut) {
                r.executionStatus = "TIMEOUT";
            } else if (execute.exitCode == 0) {
                r.executionStatus = "PASS";
            } else {
                r.executionStatus = "FAIL";
            }

            if (!Files.isRegularFile(execFile)) {
                r.status = "JACOCO_EXEC_NOT_CREATED";
                r.diagnostic =
                        "Execution status=" + r.executionStatus
                                + " | " + readSmall(runLog);
                writeDetail(detailRoot, r);
                return r;
            }

            Path xml = work.resolve("jacoco.xml");
            Path reportLog = work.resolve("report.log");

            List<String> reportCmd =
                    List.of(
                            javaExe.toString(),
                            "-jar",
                            cliJar.toString(),
                            "report",
                            execFile.toString(),
                            "--classfiles",
                            classes.toString(),
                            "--xml",
                            xml.toString()
                    );

            ProcessResult report =
                    runProcess(
                            reportCmd,
                            project,
                            reportLog,
                            REPORT_TIMEOUT_SECONDS);

            if (report.timedOut
                    || report.exitCode != 0
                    || !Files.isRegularFile(xml)) {
                r.status = "JACOCO_REPORT_FAIL";
                r.diagnostic =
                        "CLI exit=" + report.exitCode
                                + " timeout=" + report.timedOut
                                + " | " + readSmall(reportLog);
                writeDetail(detailRoot, r);
                return r;
            }

            MethodCoverage coverage =
                    readTargetMethodCoverage(
                            xml,
                            target);

            if (coverage == null) {
                r.status = "TARGET_NOT_FOUND_IN_JACOCO_XML";
                r.diagnostic =
                        "Target class=" + target.binaryClassName
                                + ", method=" + target.methodName
                                + ", source line=" + target.startLine;
                writeDetail(detailRoot, r);
                return r;
            }

            r.instructionMissed = coverage.instructionMissed;
            r.instructionCovered = coverage.instructionCovered;
            r.branchMissed = coverage.branchMissed;
            r.branchCovered = coverage.branchCovered;
            r.lineMissed = coverage.lineMissed;
            r.lineCovered = coverage.lineCovered;

            r.instructionCoverage =
                    coveragePercent(
                            r.instructionCovered,
                            r.instructionMissed);

            r.branchCoverage =
                    coveragePercentOrNa(
                            r.branchCovered,
                            r.branchMissed);

            r.lineCoverage =
                    coveragePercent(
                            r.lineCovered,
                            r.lineMissed);

            r.targetInvoked =
                    r.instructionCovered > 0;

            r.status =
                    "TARGET_COVERAGE_RESOLVED";

            r.diagnostic =
                    "JaCoCo method line="
                            + coverage.methodLine
                            + " | descriptor="
                            + coverage.descriptor
                            + " | runtime="
                            + r.executionStatus;

            writeDetail(detailRoot, r);
            return r;

        } catch (Throwable t) {
            r.status = "EXCEPTION";
            r.diagnostic =
                    t.getClass().getSimpleName()
                            + ": "
                            + normalize(t.getMessage());

            try {
                writeDetail(detailRoot, r);
            } catch (Exception ignored) {}

            return r;
        }
    }

    private static TargetInfo resolveTargetInfo(
            Path source,
            String authoritativeFunction)
            throws Exception {

        CompilationUnit cu =
                StaticJavaParser.parse(source);

        List<String> lines =
                Files.readAllLines(
                        source,
                        StandardCharsets.UTF_8);

        List<LineRange> markerRanges =
                originalMethodMarkerRanges(lines);

        List<MethodDeclaration> methods =
                cu.findAll(MethodDeclaration.class);

        String function =
                normalize(authoritativeFunction);

        String targetMethod =
                function.contains(".")
                        ? function.substring(
                                function.lastIndexOf('.') + 1)
                        : function;

        String targetClass =
                function.contains(".")
                        ? function.substring(
                                0,
                                function.lastIndexOf('.'))
                        : "";

        String targetClassSimple =
                targetClass.contains(".")
                        ? targetClass.substring(
                                targetClass.lastIndexOf('.') + 1)
                        : targetClass;

        List<MethodDeclaration> markerCandidates =
                methods.stream()
                        .filter(m ->
                                m.getBegin()
                                        .map(p ->
                                                inAnyRange(
                                                        p.line,
                                                        markerRanges))
                                        .orElse(false))
                        .filter(m ->
                                enclosingMethodDepth(m) == 0)
                        .toList();

        MethodDeclaration selected = null;

        if (!targetMethod.isBlank()) {

            List<MethodDeclaration> markerNameMatches =
                    markerCandidates.stream()
                            .filter(m ->
                                    m.getNameAsString()
                                            .equals(targetMethod))
                            .toList();

            if (markerNameMatches.size() == 1) {
                selected = markerNameMatches.get(0);
            }
        }

        if (selected == null
                && targetMethod.isBlank()
                && markerCandidates.size() == 1) {

            selected = markerCandidates.get(0);
        }

        if (selected == null
                && !targetMethod.isBlank()) {

            List<MethodDeclaration> nameCandidates =
                    methods.stream()
                            .filter(m ->
                                    m.getNameAsString()
                                            .equals(targetMethod))
                            .toList();

            if (nameCandidates.size() == 1) {

                selected = nameCandidates.get(0);

            } else if (!nameCandidates.isEmpty()) {

                if (!targetClassSimple.isBlank()) {

                    List<MethodDeclaration> ownerMatches =
                            nameCandidates.stream()
                                    .filter(m ->
                                            enclosingSimpleTypeName(m)
                                                    .equals(targetClassSimple))
                                    .toList();

                    if (ownerMatches.size() == 1) {
                        selected = ownerMatches.get(0);
                    }
                }

                if (selected == null) {

                    int minimumDepth =
                            nameCandidates.stream()
                                    .mapToInt(
                                            FinalRQ1JacocoMain::typeNestingDepth)
                                    .min()
                                    .orElse(Integer.MAX_VALUE);

                    List<MethodDeclaration> shallowest =
                            nameCandidates.stream()
                                    .filter(m ->
                                            typeNestingDepth(m)
                                                    == minimumDepth)
                                    .toList();

                    if (shallowest.size() == 1) {
                        selected = shallowest.get(0);
                    }
                }

                if (selected == null) {

                    int maximumArity =
                            nameCandidates.stream()
                                    .mapToInt(m ->
                                            m.getParameters().size())
                                    .max()
                                    .orElse(-1);

                    List<MethodDeclaration> arityCandidates =
                            nameCandidates.stream()
                                    .filter(m ->
                                            m.getParameters().size()
                                                    == maximumArity)
                                    .toList();

                    if (arityCandidates.size() == 1) {
                        selected = arityCandidates.get(0);
                    }
                }

                if (selected == null
                        && !markerRanges.isEmpty()) {

                    List<MethodDeclaration> markedNameCandidates =
                            nameCandidates.stream()
                                    .filter(m ->
                                            m.getBegin()
                                                    .map(p ->
                                                            inAnyRange(
                                                                    p.line,
                                                                    markerRanges))
                                                    .orElse(false))
                                    .filter(m ->
                                            enclosingMethodDepth(m) == 0)
                                    .toList();

                    if (markedNameCandidates.size() == 1) {
                        selected = markedNameCandidates.get(0);
                    }
                }
            }
        }

        if (selected == null) {
            return null;
        }

        if (!targetMethod.isBlank()
                && !selected.getNameAsString()
                        .equals(targetMethod)) {

            return null;
        }

        int startLine =
                selected.getBegin()
                        .map(p -> p.line)
                        .orElse(-1);

        String binaryClass =
                binaryClassName(
                        cu,
                        selected);

        return new TargetInfo(
                selected.getNameAsString(),
                binaryClass,
                startLine
        );
    }

    private static int enclosingMethodDepth(
            MethodDeclaration method) {

        int depth = 0;

        com.github.javaparser.ast.Node node =
                method;

        while ((node =
                        node.getParentNode()
                                .orElse(null))
                != null) {

            if (node instanceof MethodDeclaration) {
                depth++;
            }
        }

        return depth;
    }

    private static List<LineRange> originalMethodMarkerRanges(
            List<String> lines) {

        List<LineRange> ranges =
                new ArrayList<>();

        Integer start = null;

        for (int i = 0; i < lines.size(); i++) {
            String x =
                    lines.get(i)
                            .toUpperCase(Locale.ROOT);

            boolean original =
                    x.contains("ORIGINAL")
                            && (x.contains("CODESEARCHNET")
                            || x.contains("TARGET METHOD")
                            || x.contains("METHOD"));

            if (original && x.contains("START")) {
                start = i + 2;
            }

            if (start != null
                    && original
                    && x.contains("END")) {

                int end = i;
                ranges.add(new LineRange(start, end));
                start = null;
            }
        }

        return ranges;
    }

    private static boolean inAnyRange(
            int line,
            List<LineRange> ranges) {

        for (LineRange r : ranges) {
            if (line >= r.start && line <= r.end) {
                return true;
            }
        }

        return false;
    }

    private static MainInfo resolveMainInfo(Path source)
            throws Exception {

        CompilationUnit cu =
                StaticJavaParser.parse(source);

        List<MethodDeclaration> mains =
                cu.findAll(MethodDeclaration.class)
                        .stream()
                        .filter(FinalRQ1JacocoMain::looksLikeMain)
                        .toList();

        if (mains.isEmpty()) {
            return null;
        }

        MethodDeclaration selected =
                mains.stream()
                        .min(Comparator.comparingInt(
                                FinalRQ1JacocoMain::typeNestingDepth))
                        .orElse(mains.get(0));

        return new MainInfo(
                binaryClassName(cu, selected)
        );
    }

    private static boolean looksLikeMain(
            MethodDeclaration m) {

        if (!m.getNameAsString().equals("main")) {
            return false;
        }

        if (!m.isStatic()) {
            return false;
        }

        if (!m.getType().isVoidType()) {
            return false;
        }

        if (m.getParameters().size() != 1) {
            return false;
        }

        Parameter p =
                m.getParameter(0);

        String t =
                p.getType().asString()
                        .replace("java.lang.", "")
                        .replace(" ", "");

        return t.equals("String[]")
                || (p.isVarArgs()
                && t.equals("String"));
    }

    private static int typeNestingDepth(
            MethodDeclaration m) {

        int depth = 0;
        com.github.javaparser.ast.Node node = m;

        while ((node = node.getParentNode().orElse(null))
                != null) {
            if (node instanceof TypeDeclaration<?>) {
                depth++;
            }
        }

        return depth;
    }

    private static String binaryClassName(
            CompilationUnit cu,
            MethodDeclaration method) {

        List<String> types =
                new ArrayList<>();

        com.github.javaparser.ast.Node node = method;

        while ((node = node.getParentNode().orElse(null))
                != null) {

            if (node instanceof TypeDeclaration<?> td) {
                types.add(0, td.getNameAsString());
            }
        }

        if (types.isEmpty()) {
            throw new IllegalStateException(
                    "Method has no enclosing named type: "
                            + method.getDeclarationAsString());
        }

        String classPart =
                String.join("$", types);

        String pkg =
                cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString())
                        .orElse("");

        return pkg.isBlank()
                ? classPart
                : pkg + "." + classPart;
    }

    private static String enclosingSimpleTypeName(
            MethodDeclaration method) {

        com.github.javaparser.ast.Node node = method;

        while ((node = node.getParentNode().orElse(null))
                != null) {

            if (node instanceof TypeDeclaration<?> td) {
                return td.getNameAsString();
            }
        }

        return "";
    }

    private static MethodCoverage readTargetMethodCoverage(
            Path xml,
            TargetInfo target)
            throws Exception {

        DocumentBuilderFactory f =
                DocumentBuilderFactory.newInstance();

        try {
            f.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
        } catch (Exception ignored) {
        }

        try {
            f.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false);
        } catch (Exception ignored) {
        }

        try {
            f.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false);
        } catch (Exception ignored) {
        }

        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        var builder =
                f.newDocumentBuilder();

        builder.setEntityResolver(
                (publicId, systemId) ->
                        new InputSource(
                                new StringReader("")
                        )
        );

        Document doc =
                builder.parse(
                        xml.toFile()
                );

        String expectedClass =
                target.binaryClassName
                        .replace('.', '/');

        NodeList classes =
                doc.getElementsByTagName("class");

        List<Element> classMatches =
                new ArrayList<>();

        for (int i = 0; i < classes.getLength(); i++) {
            Element c =
                    (Element) classes.item(i);

            if (c.getAttribute("name")
                    .equals(expectedClass)) {

                classMatches.add(c);
            }
        }

        if (classMatches.isEmpty()) {

            String simple =
                    expectedClass.contains("/")
                            ? expectedClass.substring(
                                    expectedClass.lastIndexOf('/') + 1)
                            : expectedClass;

            for (int i = 0; i < classes.getLength(); i++) {
                Element c =
                        (Element) classes.item(i);

                String actual =
                        c.getAttribute("name");

                if (actual.equals(simple)
                        || actual.endsWith("/" + simple)) {
                    classMatches.add(c);
                }
            }
        }

        List<Element> methods =
                new ArrayList<>();

        for (Element c : classMatches) {
            NodeList children =
                    c.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);

                if (n instanceof Element e
                        && e.getTagName().equals("method")
                        && e.getAttribute("name")
                                .equals(target.methodName)) {

                    methods.add(e);
                }
            }
        }

        if (methods.isEmpty()) {
            return null;
        }

        Element selected;

        if (methods.size() == 1) {
            selected = methods.get(0);

        } else {
            selected =
                    methods.stream()
                            .min(Comparator.comparingInt(
                                    e -> lineDistance(
                                            integerAttr(e, "line", -1),
                                            target.startLine)))
                            .orElse(methods.get(0));
        }

        int im = 0;
        int ic = 0;
        int bm = 0;
        int bc = 0;
        int lm = 0;
        int lc = 0;

        NodeList counters =
                selected.getChildNodes();

        for (int i = 0; i < counters.getLength(); i++) {
            Node n = counters.item(i);

            if (!(n instanceof Element e)
                    || !e.getTagName().equals("counter")) {
                continue;
            }

            String type = e.getAttribute("type");

            int missed =
                    integerAttr(e, "missed", 0);

            int covered =
                    integerAttr(e, "covered", 0);

            switch (type) {
                case "INSTRUCTION" -> {
                    im = missed;
                    ic = covered;
                }
                case "BRANCH" -> {
                    bm = missed;
                    bc = covered;
                }
                case "LINE" -> {
                    lm = missed;
                    lc = covered;
                }
                default -> {
                }
            }
        }

        return new MethodCoverage(
                im,
                ic,
                bm,
                bc,
                lm,
                lc,
                integerAttr(selected, "line", -1),
                selected.getAttribute("desc")
        );
    }

    private static int lineDistance(
            int jacocoLine,
            int sourceLine) {

        if (jacocoLine < 0 || sourceLine < 0) {
            return Integer.MAX_VALUE / 2;
        }

        return Math.abs(jacocoLine - sourceLine);
    }

    private static int integerAttr(
            Element e,
            String name,
            int fallback) {

        try {
            String x = e.getAttribute(name);

            return x == null || x.isBlank()
                    ? fallback
                    : Integer.parseInt(x);

        } catch (Exception ex) {
            return fallback;
        }
    }

    private static List<FinalObservation> readFinalObservations(
            Path csv)
            throws Exception {

        CsvTable table =
                CsvTable.read(csv);

        String llm = table.require("llm");
        String repo = table.require("repo");
        String method = table.require("method");
        String global = table.optional("global_method");
        String function = table.require("function");
        String file = table.require("file");

        List<FinalObservation> out =
                new ArrayList<>();

        for (Map<String, String> r : table.rows) {
            out.add(new FinalObservation(
                    lower(r.get(llm)),
                    integer(r.get(repo)),
                    integer(r.get(method)),
                    global == null ? 0 : integer(r.get(global)),
                    text(r.get(function)),
                    text(r.get(file))
            ));
        }

        out.sort(
                Comparator
                        .comparing(
                                (FinalObservation r) -> r.llm)
                        .thenComparingInt(r -> r.repo)
                        .thenComparingInt(r -> r.method));

        return out;
    }

    private static void validateObservationUniverse(
            List<FinalObservation> rows) {

        Set<String> seen =
                new LinkedHashSet<>();

        for (FinalObservation r : rows) {
            String key =
                    r.llm + "|" + r.repo + ":" + r.method;

            if (!seen.add(key)) {
                throw new IllegalStateException(
                        "Duplicate final observation: " + key);
            }
        }

        for (String llm : LLMS) {
            long n =
                    rows.stream()
                            .filter(r -> r.llm.equals(llm))
                            .count();

            if (n != 100) {
                throw new IllegalStateException(
                        llm + " has " + n
                                + " observations; expected 100.");
            }
        }
    }

    private static Map<String, Path> indexGeneratedFiles(
            Path methodsRoot)
            throws Exception {

        Map<String, Path> out =
                new LinkedHashMap<>();

        for (String llm : LLMS) {
            Path root =
                    methodsRoot.resolve(llm);

            if (!Files.isDirectory(root)) {
                continue;
            }

            try (var stream = Files.walk(root)) {
                for (Path p :
                        stream.filter(Files::isRegularFile)
                                .filter(x ->
                                        x.toString()
                                                .toLowerCase(Locale.ROOT)
                                                .endsWith(".java"))
                                .toList()) {

                    String filename =
                            normalizeFile(
                                    p.getFileName().toString());

                    String repoFolder =
                            nearestRepoFolder(root, p);

                    if (!repoFolder.isBlank()) {
                        out.putIfAbsent(
                                llm + "|" + repoFolder + "|" + filename,
                                p);
                    }

                    String loose =
                            llm + "|*|" + filename;

                    Path previous =
                            out.get(loose);

                    if (previous == null) {
                        out.put(loose, p);
                    } else if (!previous.equals(p)) {

                        out.remove(loose);
                    }
                }
            }
        }

        return out;
    }

    private static Path findGeneratedFile(
            FinalObservation obs,
            Map<String, Path> index) {

        String filename =
                normalizeFile(obs.file);

        String repo =
                "repo" + obs.repo;

        Path exact =
                index.get(
                        obs.llm + "|" + repo + "|" + filename);

        if (exact != null) {
            return exact;
        }

        return index.get(
                obs.llm + "|*|" + filename);
    }

    private static String nearestRepoFolder(
            Path llmRoot,
            Path file) {

        Path x = file.getParent();

        while (x != null
                && !x.equals(llmRoot.getParent())) {

            if (x.getFileName() != null) {
                String name =
                        x.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT);

                if (name.matches("repo\\d+")) {
                    return name;
                }
            }

            if (x.equals(llmRoot)) {
                break;
            }

            x = x.getParent();
        }

        return "";
    }

    private static ProcessResult runProcess(
            List<String> command,
            Path workingDirectory,
            Path log,
            long timeoutSeconds)
            throws Exception {

        Files.createDirectories(
                log.getParent());

        long start =
                System.nanoTime();

        ProcessBuilder pb =
                new ProcessBuilder(command);

        pb.directory(
                workingDirectory.toFile());

        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());

        Process process = pb.start();

        boolean finished =
                process.waitFor(
                        timeoutSeconds,
                        TimeUnit.SECONDS);

        boolean timedOut = !finished;

        int exit;

        if (timedOut) {
            process.destroyForcibly();
            process.waitFor(
                    5,
                    TimeUnit.SECONDS);

            exit = -1;
        } else {
            exit = process.exitValue();
        }

        long ms =
                Duration.ofNanos(
                        System.nanoTime() - start)
                        .toMillis();

        return new ProcessResult(
                exit,
                timedOut,
                ms);
    }

    private static void writeAll500(
            Path file,
            List<CoverageRow> rows)
            throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8)) {

            w.write(
                    "llm,repo,method,global_method,function,file,"
                            + "source_path,target_class,target_method,target_start_line,"
                            + "main_class,compile_status,compile_exit,compile_ms,"
                            + "execution_status,execution_exit,execution_ms,"
                            + "target_invoked,"
                            + "instruction_missed,instruction_covered,instruction_coverage_pct,"
                            + "branch_missed,branch_covered,branch_coverage_pct,"
                            + "line_missed,line_covered,line_coverage_pct,"
                            + "status,diagnostic");

            w.newLine();

            for (CoverageRow r : rows) {
                w.write(csv(
                        r.obs.llm,
                        r.obs.repo,
                        r.obs.method,
                        r.obs.globalMethod,
                        r.obs.function,
                        r.obs.file,
                        r.sourcePath,
                        r.targetClass,
                        r.targetMethod,
                        r.targetStartLine,
                        r.mainClass,
                        r.compileStatus,
                        r.compileExit,
                        r.compileMs,
                        r.executionStatus,
                        r.executionExit,
                        r.executionMs,
                        r.targetInvoked,
                        r.instructionMissed,
                        r.instructionCovered,
                        formatCoverage(r.instructionCoverage),
                        r.branchMissed,
                        r.branchCovered,
                        formatCoverage(r.branchCoverage),
                        r.lineMissed,
                        r.lineCovered,
                        formatCoverage(r.lineCoverage),
                        r.status,
                        r.diagnostic
                ));

                w.newLine();
            }
        }
    }

    private static void writeLlmSummary(
            Path file,
            List<CoverageRow> rows)
            throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8)) {

            w.write(
                    "llm,methods,"
                            + "jacoco_compile_pass,jacoco_compile_rate_pct,"
                            + "jacoco_execution_pass,jacoco_execution_rate_pct,"
                            + "target_coverage_resolved,target_invoked,target_invocation_rate_pct,"
                            + "instruction_missed,instruction_covered,instruction_coverage_pct,"
                            + "branch_missed,branch_covered,branch_coverage_pct,"
                            + "line_missed,line_covered,line_coverage_pct,"
                            + "mean_method_instruction_coverage_pct,"
                            + "mean_method_branch_coverage_pct,"
                            + "mean_method_line_coverage_pct");

            w.newLine();

            for (String llm : LLMS) {
                List<CoverageRow> x =
                        rows.stream()
                                .filter(r ->
                                        r.obs.llm.equals(llm))
                                .toList();

                int n = x.size();

                long compilePass =
                        x.stream()
                                .filter(r ->
                                        r.compileStatus.equals("PASS"))
                                .count();

                long executePass =
                        x.stream()
                                .filter(r ->
                                        r.executionStatus.equals("PASS"))
                                .count();

                List<CoverageRow> resolved =
                        x.stream()
                                .filter(r ->
                                        r.status.equals(
                                                "TARGET_COVERAGE_RESOLVED"))
                                .toList();

                long invoked =
                        resolved.stream()
                                .filter(r -> r.targetInvoked)
                                .count();

                long im =
                        resolved.stream()
                                .mapToLong(r ->
                                        r.instructionMissed)
                                .sum();

                long ic =
                        resolved.stream()
                                .mapToLong(r ->
                                        r.instructionCovered)
                                .sum();

                long bm =
                        resolved.stream()
                                .mapToLong(r ->
                                        r.branchMissed)
                                .sum();

                long bc =
                        resolved.stream()
                                .mapToLong(r ->
                                        r.branchCovered)
                                .sum();

                long lm =
                        resolved.stream()
                                .mapToLong(r ->
                                        r.lineMissed)
                                .sum();

                long lc =
                        resolved.stream()
                                .mapToLong(r ->
                                        r.lineCovered)
                                .sum();

                double meanInstruction =
                        resolved.stream()
                                .mapToDouble(r ->
                                        r.instructionCoverage)
                                .filter(v ->
                                        !Double.isNaN(v))
                                .average()
                                .orElse(Double.NaN);

                double meanBranch =
                        resolved.stream()
                                .mapToDouble(r ->
                                        r.branchCoverage)
                                .filter(v ->
                                        !Double.isNaN(v))
                                .average()
                                .orElse(Double.NaN);

                double meanLine =
                        resolved.stream()
                                .mapToDouble(r ->
                                        r.lineCoverage)
                                .filter(v ->
                                        !Double.isNaN(v))
                                .average()
                                .orElse(Double.NaN);

                w.write(csv(
                        llm,
                        n,
                        compilePass,
                        pct(compilePass, n),
                        executePass,
                        pct(executePass, n),
                        resolved.size(),
                        invoked,
                        pct(invoked, n),
                        im,
                        ic,
                        formatCoverage(
                                coveragePercent(ic, im)),
                        bm,
                        bc,
                        formatCoverage(
                                coveragePercentOrNa(bc, bm)),
                        lm,
                        lc,
                        formatCoverage(
                                coveragePercent(lc, lm)),
                        formatCoverage(meanInstruction),
                        formatCoverage(meanBranch),
                        formatCoverage(meanLine)
                ));

                w.newLine();
            }
        }
    }

    private static void writeSummary(
            Path file,
            List<CoverageRow> rows)
            throws Exception {

        StringBuilder b =
                new StringBuilder();

        b.append(
                "FINAL RQ1 — TARGET-METHOD JACOCO COVERAGE\n");
        b.append(
                "==========================================\n\n");

        b.append(
                "Population: same 500 final method-LLM observations used by RQ2.\n");
        b.append(
                "Coverage unit: ORIGINAL TARGET METHOD only, not the whole generated class/file.\n");
        b.append(
                "Target invoked: JaCoCo covered instruction count > 0 for the target method.\n\n");

        for (String llm : LLMS) {
            List<CoverageRow> x =
                    rows.stream()
                            .filter(r ->
                                    r.obs.llm.equals(llm))
                            .toList();

            long cp =
                    x.stream()
                            .filter(r ->
                                    r.compileStatus.equals("PASS"))
                            .count();

            long ep =
                    x.stream()
                            .filter(r ->
                                    r.executionStatus.equals("PASS"))
                            .count();

            long resolved =
                    x.stream()
                            .filter(r ->
                                    r.status.equals(
                                            "TARGET_COVERAGE_RESOLVED"))
                            .count();

            long invoked =
                    x.stream()
                            .filter(r ->
                                    r.status.equals(
                                            "TARGET_COVERAGE_RESOLVED"))
                            .filter(r ->
                                    r.targetInvoked)
                            .count();

            b.append(String.format(
                    Locale.ROOT,
                    "%-8s methods=%3d compile=%3d execute=%3d coverage_resolved=%3d target_invoked=%3d%n",
                    llm,
                    x.size(),
                    cp,
                    ep,
                    resolved,
                    invoked));
        }

        b.append(
                "\nFiles:\n");
        b.append(
                "- TARGET_METHOD_COVERAGE_ALL_500.csv\n");
        b.append(
                "- TARGET_METHOD_COVERAGE_BY_LLM.csv\n");
        b.append(
                "- detailed/<llm>/repoN/method_N.txt\n");
        b.append(
                "- detailed/<llm>/repoN/method_N.csv\n\n");

        b.append(
                "Important: JaCoCo coverage is runtime evidence only. "
                        + "It does not establish repository dependency fidelity; "
                        + "that is evaluated separately in RQ2.\n");

        Files.writeString(
                file,
                b.toString(),
                StandardCharsets.UTF_8);
    }

    private static void writeDetail(
            Path root,
            CoverageRow r)
            throws Exception {

        Path dir =
                root.resolve(r.obs.llm)
                        .resolve("repo" + r.obs.repo);

        Files.createDirectories(dir);

        Path file =
                dir.resolve(
                        "method_" + r.obs.method + ".txt");

        String text =
                """
                FINAL RQ1 TARGET-METHOD JACOCO
                ===============================

                LLM: %s
                Repository: repo%d
                Method: %d
                Global method: %d
                Function: %s
                Generated file: %s
                Source: %s

                TARGET
                ------
                Resolved class: %s
                Resolved method: %s
                Source start line: %d

                EXECUTION HARNESS
                -----------------
                Main class: %s
                Compile: %s (exit=%d, ms=%d)
                Execute: %s (exit=%d, ms=%d)

                TARGET-METHOD COVERAGE
                ----------------------
                Target invoked: %s

                Instructions: covered=%d missed=%d coverage=%s%%
                Branches:     covered=%d missed=%d coverage=%s
                Lines:        covered=%d missed=%d coverage=%s%%

                Status: %s
                Diagnostic: %s
                """.formatted(
                        r.obs.llm,
                        r.obs.repo,
                        r.obs.method,
                        r.obs.globalMethod,
                        r.obs.function,
                        r.obs.file,
                        r.sourcePath,
                        r.targetClass,
                        r.targetMethod,
                        r.targetStartLine,
                        r.mainClass,
                        r.compileStatus,
                        r.compileExit,
                        r.compileMs,
                        r.executionStatus,
                        r.executionExit,
                        r.executionMs,
                        r.targetInvoked,
                        r.instructionCovered,
                        r.instructionMissed,
                        formatCoverage(r.instructionCoverage),
                        r.branchCovered,
                        r.branchMissed,
                        Double.isNaN(r.branchCoverage)
                                ? "N/A"
                                : formatCoverage(r.branchCoverage) + "%",
                        r.lineCovered,
                        r.lineMissed,
                        formatCoverage(r.lineCoverage),
                        r.status,
                        r.diagnostic
                );

        Files.writeString(
                file,
                text,
                StandardCharsets.UTF_8);

        Path csvFile =
                dir.resolve(
                        "method_" + r.obs.method + ".csv");

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             csvFile,
                             StandardCharsets.UTF_8)) {

            w.write(
                    "llm,repo,method,global_method,function,generated_file,"
                            + "source_path,target_class,target_method,target_start_line,"
                            + "main_class,compile_status,compile_exit,compile_ms,"
                            + "execution_status,execution_exit,execution_ms,target_invoked,"
                            + "instruction_missed,instruction_covered,instruction_coverage_pct,"
                            + "branch_missed,branch_covered,branch_coverage_pct,"
                            + "line_missed,line_covered,line_coverage_pct,status,diagnostic");

            w.newLine();

            w.write(csv(
                    r.obs.llm,
                    r.obs.repo,
                    r.obs.method,
                    r.obs.globalMethod,
                    r.obs.function,
                    r.obs.file,
                    r.sourcePath,
                    r.targetClass,
                    r.targetMethod,
                    r.targetStartLine,
                    r.mainClass,
                    r.compileStatus,
                    r.compileExit,
                    r.compileMs,
                    r.executionStatus,
                    r.executionExit,
                    r.executionMs,
                    r.targetInvoked,
                    r.instructionMissed,
                    r.instructionCovered,
                    formatCoverage(r.instructionCoverage),
                    r.branchMissed,
                    r.branchCovered,
                    formatCoverage(r.branchCoverage),
                    r.lineMissed,
                    r.lineCovered,
                    formatCoverage(r.lineCoverage),
                    r.status,
                    r.diagnostic
            ));

            w.newLine();
        }
    }

    private static void printConsoleSummary(
            List<CoverageRow> rows) {

        for (String llm : LLMS) {
            List<CoverageRow> x =
                    rows.stream()
                            .filter(r ->
                                    r.obs.llm.equals(llm))
                            .toList();

            long compile =
                    x.stream()
                            .filter(r ->
                                    r.compileStatus.equals("PASS"))
                            .count();

            long execute =
                    x.stream()
                            .filter(r ->
                                    r.executionStatus.equals("PASS"))
                            .count();

            List<CoverageRow> resolved =
                    x.stream()
                            .filter(r ->
                                    r.status.equals(
                                            "TARGET_COVERAGE_RESOLVED"))
                            .toList();

            long invoked =
                    resolved.stream()
                            .filter(r -> r.targetInvoked)
                            .count();

            long im =
                    resolved.stream()
                            .mapToLong(r ->
                                    r.instructionMissed)
                            .sum();

            long ic =
                    resolved.stream()
                            .mapToLong(r ->
                                    r.instructionCovered)
                            .sum();

            long bm =
                    resolved.stream()
                            .mapToLong(r ->
                                    r.branchMissed)
                            .sum();

            long bc =
                    resolved.stream()
                            .mapToLong(r ->
                                    r.branchCovered)
                            .sum();

            long lm =
                    resolved.stream()
                            .mapToLong(r ->
                                    r.lineMissed)
                            .sum();

            long lc =
                    resolved.stream()
                            .mapToLong(r ->
                                    r.lineCovered)
                            .sum();

            System.out.printf(
                    Locale.ROOT,
                    "%-8s methods=%3d compile=%3d/%3d execute=%3d/%3d resolved=%3d/%3d invoked=%3d/%3d instruction=%s%% branch=%s line=%s%%%n",
                    llm,
                    x.size(),
                    compile,
                    x.size(),
                    execute,
                    x.size(),
                    resolved.size(),
                    x.size(),
                    invoked,
                    x.size(),
                    formatCoverage(
                            coveragePercent(ic, im)),
                    Double.isNaN(
                            coveragePercentOrNa(bc, bm))
                            ? "N/A"
                            : formatCoverage(
                                    coveragePercentOrNa(bc, bm)) + "%",
                    formatCoverage(
                            coveragePercent(lc, lm))
            );
        }

        Map<String, Long> status =
                rows.stream()
                        .collect(
                                Collectors.groupingBy(
                                        r -> r.status,
                                        LinkedHashMap::new,
                                        Collectors.counting()));

        System.out.println();
        System.out.println("Status counts:");

        status.forEach(
                (k, v) ->
                        System.out.println(
                                "  " + k + " = " + v));
    }

    private static Path locateJacocoAgent() {
        Path home =
                Path.of(
                        System.getProperty("user.home"));

        Path p =
                home.resolve(".m2")
                        .resolve("repository")
                        .resolve("org")
                        .resolve("jacoco")
                        .resolve("org.jacoco.agent")
                        .resolve(JACOCO_VERSION)
                        .resolve(
                                "org.jacoco.agent-"
                                        + JACOCO_VERSION
                                        + "-runtime.jar");

        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException(
                    "JaCoCo agent jar not found:\n"
                            + p
                            + "\n\nRun:\n"
                            + "mvn dependency:get "
                            + "\"-Dartifact=org.jacoco:org.jacoco.agent:"
                            + JACOCO_VERSION
                            + ":jar:runtime\"");
        }

        return p.toAbsolutePath().normalize();
    }

    private static Path locateJacocoCli() {
        Path home =
                Path.of(
                        System.getProperty("user.home"));

        Path p =
                home.resolve(".m2")
                        .resolve("repository")
                        .resolve("org")
                        .resolve("jacoco")
                        .resolve("org.jacoco.cli")
                        .resolve(JACOCO_VERSION)
                        .resolve(
                                "org.jacoco.cli-"
                                        + JACOCO_VERSION
                                        + "-nodeps.jar");

        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException(
                    "JaCoCo CLI jar not found:\n"
                            + p
                            + "\n\nRun:\n"
                            + "mvn dependency:get "
                            + "\"-Dartifact=org.jacoco:org.jacoco.cli:"
                            + JACOCO_VERSION
                            + ":jar:nodeps\"");
        }

        return p.toAbsolutePath().normalize();
    }

    private static double coveragePercent(
            long covered,
            long missed) {

        long total = covered + missed;

        return total == 0
                ? 0.0
                : 100.0
                * covered
                / total;
    }

    private static double coveragePercentOrNa(
            long covered,
            long missed) {

        long total = covered + missed;

        return total == 0
                ? Double.NaN
                : 100.0
                * covered
                / total;
    }

    private static String formatCoverage(
            double value) {

        if (Double.isNaN(value)) {
            return "N/A";
        }

        return String.format(
                Locale.ROOT,
                "%.2f",
                value);
    }

    private static String pct(
            long numerator,
            long denominator) {

        if (denominator == 0) {
            return "0.00";
        }

        return String.format(
                Locale.ROOT,
                "%.2f",
                100.0
                        * numerator
                        / denominator);
    }

    private static Path javaExecutable(
            String name) {

        String exe =
                System.getProperty("os.name")
                        .toLowerCase(Locale.ROOT)
                        .contains("win")
                        ? name + ".exe"
                        : name;

        Path javaHome =
                Path.of(
                        System.getProperty("java.home"));

        Path candidate =
                javaHome.resolve("bin")
                        .resolve(exe);

        if (Files.isRegularFile(candidate)) {
            return candidate
                    .toAbsolutePath()
                    .normalize();
        }

        return Path.of(exe);
    }

    private static void require(
            Path file,
            String label) {

        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    label
                            + " not found:\n"
                            + file.toAbsolutePath());
        }
    }

    private static String readSmall(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }

            String s =
                    Files.readString(
                            file,
                            StandardCharsets.UTF_8);

            s = s.replace("\r", " ")
                    .replace("\n", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (s.length() > 1000) {
                return s.substring(0, 1000);
            }

            return s;

        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteRecursively(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }

            try (var stream = Files.walk(root)) {
                List<Path> paths =
                        stream.sorted(
                                        Comparator.reverseOrder())
                                .toList();

                for (Path p : paths) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {}
                }
            }

        } catch (Exception ignored) {}
    }

    private static String normalize(String x) {
        return x == null ? "" : x.trim();
    }

    private static String normalizeFile(String x) {
        String y =
                normalize(x)
                        .replace("\\", "/");

        int slash =
                y.lastIndexOf('/');

        if (slash >= 0) {
            y = y.substring(slash + 1);
        }

        return y.toLowerCase(Locale.ROOT);
    }

    private static String text(String x) {
        return x == null ? "" : x.trim();
    }

    private static String lower(String x) {
        return text(x)
                .toLowerCase(Locale.ROOT);
    }

    private static int integer(String x) {
        try {
            return Integer.parseInt(text(x));
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safe(String x) {
        String y =
                text(x)
                        .replaceAll(
                                "[^A-Za-z0-9._-]",
                                "_");

        return y.isBlank()
                ? "unknown"
                : y;
    }

    private static String csv(Object... values) {
        StringBuilder b =
                new StringBuilder();

        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(',');
            }

            String s =
                    values[i] == null
                            ? ""
                            : String.valueOf(values[i]);

            b.append('"')
                    .append(
                            s.replace(
                                    "\"",
                                    "\"\""))
                    .append('"');
        }

        return b.toString();
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

        String require(String name) {
            String x = optional(name);

            if (x == null) {
                throw new IllegalStateException(
                        "Required column '"
                                + name
                                + "' not found. Actual="
                                + columns);
            }

            return x;
        }

        String optional(String name) {
            for (String c : columns) {
                if (c.equalsIgnoreCase(name)) {
                    return c;
                }
            }

            return null;
        }

        static CsvTable read(Path file)
                throws Exception {

            try (BufferedReader reader =
                         Files.newBufferedReader(
                                 file,
                                 StandardCharsets.UTF_8)) {

                String header =
                        reader.readLine();

                if (header == null) {
                    throw new IllegalStateException(
                            "Empty CSV: " + file);
                }

                List<String> columns =
                        parseCsvLine(header);

                if (!columns.isEmpty()) {
                    columns.set(
                            0,
                            columns.get(0)
                                    .replace("\uFEFF", "")
                                    .trim());
                }

                for (int i = 1; i < columns.size(); i++) {
                    columns.set(
                            i,
                            columns.get(i).trim());
                }

                List<Map<String, String>> rows =
                        new ArrayList<>();

                String line;

                while ((line = reader.readLine())
                        != null) {

                    if (line.isBlank()) {
                        continue;
                    }

                    List<String> values =
                            parseCsvLine(line);

                    Map<String, String> row =
                            new LinkedHashMap<>();

                    for (int i = 0; i < columns.size(); i++) {
                        row.put(
                                columns.get(i),
                                i < values.size()
                                        ? values.get(i)
                                        : "");
                    }

                    rows.add(row);
                }

                return new CsvTable(
                        columns,
                        rows);
            }
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values =
                new ArrayList<>();

        StringBuilder current =
                new StringBuilder();

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

    private static void validateCoverageUniverse(
            List<CoverageRow> rows) {

        if (rows.size() != 500) {
            throw new IllegalStateException(
                    "Expected 500 RQ1 rows, found "
                            + rows.size());
        }

        for (String llm : LLMS) {
            long n =
                    rows.stream()
                            .filter(r ->
                                    r.obs.llm.equals(llm))
                            .count();

            if (n != 100) {
                throw new IllegalStateException(
                        llm + " has " + n
                                + " RQ1 rows; expected 100.");
            }
        }
    }

    private record FinalObservation(
            String llm,
            int repo,
            int method,
            int globalMethod,
            String function,
            String file) {}

    private record TargetInfo(
            String methodName,
            String binaryClassName,
            int startLine) {}

    private record MainInfo(
            String binaryClassName) {}

    private record LineRange(
            int start,
            int end) {}

    private record ProcessResult(
            int exitCode,
            boolean timedOut,
            long durationMs) {}

    private record MethodCoverage(
            int instructionMissed,
            int instructionCovered,
            int branchMissed,
            int branchCovered,
            int lineMissed,
            int lineCovered,
            int methodLine,
            String descriptor) {}

    private static final class CoverageRow {
        final FinalObservation obs;

        String sourcePath = "";
        String targetClass = "";
        String targetMethod = "";
        int targetStartLine = -1;
        String mainClass = "";

        String compileStatus = "NOT_RUN";
        int compileExit = -1;
        long compileMs = 0;

        String executionStatus = "NOT_RUN";
        int executionExit = -1;
        long executionMs = 0;

        boolean targetInvoked = false;

        int instructionMissed = 0;
        int instructionCovered = 0;
        double instructionCoverage = Double.NaN;

        int branchMissed = 0;
        int branchCovered = 0;
        double branchCoverage = Double.NaN;

        int lineMissed = 0;
        int lineCovered = 0;
        double lineCoverage = Double.NaN;

        String status = "NOT_STARTED";
        String diagnostic = "";

        CoverageRow(FinalObservation obs) {
            this.obs = obs;
        }
    }
}
