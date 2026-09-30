package org.thesis.eval;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class FinalDependencyEvaluationMain {

    private static final List<String> LLMS =
            List.of(
                    "claude",
                    "deepseek",
                    "gemini",
                    "gpt",
                    "qwen"
            );

    private static final Set<String> PRIMITIVES =
            Set.of(
                    "byte",
                    "short",
                    "int",
                    "long",
                    "float",
                    "double",
                    "boolean",
                    "char",
                    "void"
            );

    private static final Map<String, String> VERIFIED_MAPPING_OVERRIDES =
            new LinkedHashMap<>();

    static {

        VERIFIED_MAPPING_OVERRIDES.put(
                "qwen|method_2_traj_qwen.java",
                "15:2"
        );
    }

    private FinalDependencyEvaluationMain() {
    }

    public static void main(String[] args) {

        try {

            Path project =
                    args.length == 0
                            ? Path.of(".")
                            : Path.of(args[0]);

            project =
                    project
                            .toAbsolutePath()
                            .normalize();

            Path results =
                    project.resolve("results");

            Path mappingCsv =
                    results
                            .resolve("RQ2")
                            .resolve("FINAL_F1_ALL_500.csv");

            Path gtCsv =
                    results
                            .resolve("RQ2")
                            .resolve("GROUND_TRUTH_RECURSIVE_EXTRACTED_ROWS.csv");

            Path manifestCsv =
                    project
                            .resolve("input")
                            .resolve("ALL_100_FINAL_METHODS_FULL_DETAILS.csv");

            Path methodsRoot =
                    project
                            .resolve("input")
                            .resolve("methods");

            Path verifiedOverrideCsv =
                    project
                            .resolve("input")
                            .resolve("VERIFIED_MAPPING_OVERRIDES_GEMINI_REPO7.csv");

            Path configFile =
                    project
                            .resolve("config")
                            .resolve("evaluation.properties");

            Path out =
                    results.resolve(
                            "RQ2"
                    );

            Path byRepo =
                    out.resolve("by_repository");

            Path byLlm =
                    out.resolve("by_llm");

            Files.createDirectories(out);
            Files.createDirectories(byRepo);
            Files.createDirectories(byLlm);

            require(mappingCsv);
            require(gtCsv);
            require(manifestCsv);
            require(configFile);

            if (!Files.isDirectory(methodsRoot)) {

                throw new IllegalStateException(
                        "Generated Java methods root missing: "
                                + methodsRoot
                );
            }

            System.out.println(
                    "============================================================"
            );
            System.out.println(
                    "FINAL DEPENDENCY EVALUATION"
            );
            System.out.println(
                    "============================================================"
            );

            System.out.println(
                    "Old F1 and old match decisions are NOT used for scoring."
            );

            System.out.println(
                    "Generated context is freshly re-extracted from the selected Java files."
            );

            List<MappingRow> mappings =
                    readMappings(mappingCsv);

            List<GroundDep> rawGt =
                    readGroundTruth(gtCsv);

            List<Models.ManifestRow> manifest =
                    ManifestLoader.load(manifestCsv);

            Config cfg =
                    new Config(configFile);

            if (manifest.size() != 100) {

                throw new IllegalStateException(
                        "Authoritative manifest must contain exactly 100 methods; found "
                                + manifest.size()
                );
            }

            Map<String, Models.ManifestRow> manifestByKey =
                    manifest.stream()
                            .collect(
                                    Collectors.toMap(
                                            Models.ManifestRow::key,
                                            x -> x,
                                            (a, b) -> a,
                                            LinkedHashMap::new
                                    )
                            );

            Set<String> authoritativeMethodKeys =
                    new LinkedHashSet<>(
                            manifestByKey.keySet()
                    );

            System.out.println(
                    "Mapping rows read: "
                            + mappings.size()
            );

            System.out.println(
                    "Raw ground-truth rows read: "
                            + rawGt.size()
            );

            System.out.println(
                    "Authoritative manifest methods: "
                            + manifest.size()
            );

            List<WarningRow> warnings =
                    new ArrayList<>();

            List<MappingAuditRow> mappingAudit =
                    new ArrayList<>();

            List<GeneratedAnalysisAuditRow> generatedAudit =
                    new ArrayList<>();

            List<TargetSelfExclusion> targetSelfExclusions =
                    new ArrayList<>();

            loadVerifiedMappingOverrides(
                    verifiedOverrideCsv,
                    manifestByKey,
                    warnings
            );

            mappings =
                    repairWeakMappings(
                            methodsRoot,
                            mappings,
                            manifest,
                            warnings
                    );

            Map<String, List<GroundDep>> rawScoredGtByMethod =
                    buildRawScoredGroundTruth(
                            rawGt,
                            authoritativeMethodKeys,
                            targetSelfExclusions
                    );

            Map<String, List<GroundDep>> gtByMethod =
                    buildGroundTruth(
                            rawGt,
                            authoritativeMethodKeys,
                            warnings
                    );

            if (gtByMethod.size() != 100) {

                Set<String> missingGt =
                        new LinkedHashSet<>(
                                authoritativeMethodKeys
                        );

                missingGt.removeAll(
                        gtByMethod.keySet()
                );

                throw new IllegalStateException(
                        "Expected GT for all 100 authoritative methods, found "
                                + gtByMethod.size()
                                + ". Missing GT method keys="
                                + missingGt
                );
            }

            List<Observation> observations =
                    buildObservations(
                            gtByMethod,
                            mappings,
                            mappingAudit,
                            warnings
                    );

            validateSelectedFileUniqueness(
                    observations,
                    warnings
            );

            List<GenDep> rawGen =
                    freshAnalyzeGenerated(
                            project,
                            methodsRoot,
                            observations,
                            manifestByKey,
                            cfg,
                            generatedAudit,
                            warnings
                    );

            Map<String, List<GenDep>> rawScoredGenByFile =
                    buildRawScoredGenerated(
                            rawGen,
                            targetSelfExclusions
                    );

            Map<String, List<GenDep>> genByFile =
                    buildGenerated(
                            rawGen,
                            warnings
                    );

            validateNoTargetSelfDependencies(
                    gtByMethod,
                    genByFile,
                    warnings
            );

            writeFreshGeneratedCsv(
                    out,
                    rawGen
            );

            writeGeneratedAnalysisAudit(
                    out,
                    generatedAudit
            );

            writeGroundTruthRawAudit(
                    out,
                    rawGt,
                    authoritativeMethodKeys
            );

            writeGroundTruthUniqueAudit(
                    out,
                    gtByMethod
            );

            writeGeneratedUniqueAudit(
                    out,
                    genByFile
            );

            writeTargetSelfExclusions(
                    out,
                    targetSelfExclusions
            );

            if (observations.size() != 500) {

                throw new IllegalStateException(
                        "Expected 500 method-LLM observations, found "
                                + observations.size()
                );
            }

            validateObservationUniverse(
                    observations
            );

            List<MethodScore> scores =
                    new ArrayList<>();

            List<Decision> allDecisions =
                    new ArrayList<>();

            List<Extra> allExtras =
                    new ArrayList<>();

            int index =
                    0;

            for (Observation obs : observations) {

                index++;

                System.out.printf(
                        "[CLEAN %d/500] %s repo%d method_%d %s%n",
                        index,
                        obs.llm(),
                        obs.repo(),
                        obs.method(),
                        obs.function()
                );

                List<GroundDep> expected =
                        new ArrayList<>(
                                gtByMethod.getOrDefault(
                                        obs.methodKey(),
                                        List.of()
                                )
                        );

                List<GenDep> actual;

                if (obs.missing()) {

                    actual =
                            new ArrayList<>();

                } else {

                    actual =
                            new ArrayList<>(
                                    genByFile.getOrDefault(
                                            obs.fileKey(),
                                            List.of()
                                    )
                            );

                    if (actual.isEmpty()) {

                        warnings.add(
                                new WarningRow(
                                        "WARN",
                                        obs.llm(),
                                        obs.repo(),
                                        obs.method(),
                                        obs.file(),
                                        "NO_GENERATED_DEPENDENCY_ROWS",
                                        "Selected generated file has no scored target-rooted dependency rows."
                                )
                        );
                    }
                }

                MatchOutput matched =
                        matchOneToOne(
                                obs,
                                expected,
                                actual,
                                warnings
                        );

                int tp =
                        (int)
                                matched.decisions()
                                        .stream()
                                        .filter(
                                                Decision::tp
                                        )
                                        .count();

                int fn =
                        (int)
                                matched.decisions()
                                        .stream()
                                        .filter(
                                                d -> !d.tp()
                                        )
                                        .count();

                int fp =
                        matched.extras().size();

                double precision =
                        precision(
                                tp,
                                fp
                        );

                double recall =
                        recall(
                                tp,
                                fn
                        );

                double f1 =
                        f1(
                                precision,
                                recall
                        );

                int provComparable =
                        tp;

                int provCorrect =
                        (int)
                                matched.decisions()
                                        .stream()
                                        .filter(
                                                Decision::tp
                                        )
                                        .filter(
                                                Decision::provenanceCorrect
                                        )
                                        .count();

                double provAccuracy =
                        provComparable == 0
                                ? 0.0
                                : (double)
                                provCorrect
                                / provComparable;

                int rawGroundScoredCount =
                        rawScoredGtByMethod
                                .getOrDefault(
                                        obs.methodKey(),
                                        List.of()
                                )
                                .size();

                int rawGeneratedScoredCount =
                        obs.missing()
                                ? 0
                                : rawScoredGenByFile
                                .getOrDefault(
                                        obs.fileKey(),
                                        List.of()
                                )
                                .size();

                MethodScore score =
                        new MethodScore(
                                obs,

                                rawGroundScoredCount,
                                expected.size(),

                                rawGeneratedScoredCount,
                                actual.size(),

                                tp,
                                fp,
                                fn,

                                precision,
                                recall,
                                f1,

                                provCorrect,
                                provComparable,
                                provAccuracy
                        );

                scores.add(
                        score
                );

                allDecisions.addAll(
                        matched.decisions()
                );

                allExtras.addAll(
                        matched.extras()
                );

                List<GroundDep> rawGroundForMethod =
                        rawScoredGtByMethod
                                .getOrDefault(
                                        obs.methodKey(),
                                        List.of()
                                );

                List<GenDep> rawGeneratedForFile =
                        obs.missing()
                                ? List.of()
                                : rawScoredGenByFile
                                .getOrDefault(
                                        obs.fileKey(),
                                        List.of()
                                );

                writeMethodTxt(
                        byRepo,
                        score,
                        expected,
                        actual,
                        matched
                );

                writeMethodCsv(
                        byRepo,
                        score,
                        matched
                );

                writeMethodRawAndUniqueInventories(
                        byRepo,
                        score,

                        rawGroundForMethod,
                        expected,

                        rawGeneratedForFile,
                        actual
                );
            }

            validateFinalScores(
                    scores,
                    allDecisions,
                    allExtras,
                    warnings
            );

            writeCleanAll500(
                    out,
                    scores
            );

            writeAllDependencyRows(
                    out,
                    allDecisions,
                    allExtras
            );

            writeDecisionSubset(
                    out.resolve(
                            "ALL_FINAL_TP.csv"
                    ),
                    allDecisions,
                    "TP"
            );

            writeDecisionSubset(
                    out.resolve(
                            "ALL_FINAL_FN.csv"
                    ),
                    allDecisions,
                    "FN"
            );

            writeExtras(
                    out.resolve(
                            "ALL_FINAL_FP.csv"
                    ),
                    allExtras
            );

            writeByLlm(
                    out,
                    byLlm,
                    scores,
                    allDecisions,
                    allExtras
            );

            writeByRepository(
                    out,
                    byRepo,
                    scores
            );

            writeByMethod(
                    out,
                    scores
            );

            writeByKind(
                    out,
                    allDecisions,
                    allExtras
            );

            writeByDepth(
                    out,
                    allDecisions
            );

            writeMappingAudit(
                    out,
                    mappingAudit
            );

            writeWarnings(
                    out,
                    warnings
            );

            writeSummary(
                    out,
                    scores,
                    allDecisions,
                    allExtras,
                    mappingAudit,
                    warnings,
                    rawGt.size(),
                    rawGen.size(),
                    targetSelfExclusions.size()
            );

            long txtCount;

            long methodCsvCount;

            try (var s =
                         Files.walk(byRepo)) {

                txtCount =
                        s.filter(
                                        Files::isRegularFile
                                )
                                .filter(
                                        FinalDependencyEvaluationMain
                                                ::isMethodLlmTxt
                                )
                                .count();
            }

            try (var s =
                         Files.walk(byRepo)) {

                methodCsvCount =
                        s.filter(
                                        Files::isRegularFile
                                )
                                .filter(
                                        FinalDependencyEvaluationMain
                                                ::isMethodLlmCsv
                                )
                                .count();
            }

            System.out.println();

            System.out.println(
                    "Method-level repo-wise TXT reports: "
                            + txtCount
            );

            System.out.println(
                    "Method-level repo-wise CSV reports: "
                            + methodCsvCount
            );

            if (txtCount != 500
                    || methodCsvCount != 500) {

                throw new IllegalStateException(
                        "Expected exactly 500 method TXT and 500 method CSV reports, found TXT="
                                + txtCount
                                + ", CSV="
                                + methodCsvCount
                );
            }

            long finalValidationErrors =
                    warnings.stream()
                            .filter(
                                    w ->
                                            w.severity()
                                                    .equals(
                                                            "ERROR"
                                                    )
                            )
                            .count();

            if (finalValidationErrors > 0) {

                throw new IllegalStateException(
                        "FINAL VALIDATION FAILED: "
                                + finalValidationErrors
                                + " ERROR row(s) remain. Inspect "
                                + out.resolve(
                                "VALIDATION_WARNINGS.csv"
                        )
                                + ". Results were written for diagnosis but must NOT be used as final dissertation scores."
                );
            }

            int totalTp =
                    scores.stream()
                            .mapToInt(
                                    MethodScore::tp
                            )
                            .sum();

            int totalFp =
                    scores.stream()
                            .mapToInt(
                                    MethodScore::fp
                            )
                            .sum();

            int totalFn =
                    scores.stream()
                            .mapToInt(
                                    MethodScore::fn
                            )
                            .sum();

            System.out.println(
                    "============================================================"
            );

            System.out.println(
                    "FINAL DEPENDENCY SCORING CERTIFIED"
            );

            System.out.println(
                    "============================================================"
            );

            System.out.println(
                    "TP = "
                            + totalTp
            );

            System.out.println(
                    "FP = "
                            + totalFp
            );

            System.out.println(
                    "FN = "
                            + totalFn
            );

            System.out.println(
                    "Precision = "
                            + fmt(
                            precision(
                                    totalTp,
                                    totalFp
                            )
                    )
            );

            System.out.println(
                    "Recall = "
                            + fmt(
                            recall(
                                    totalTp,
                                    totalFn
                            )
                    )
            );

            System.out.println(
                    "F1 = "
                            + fmt(
                            f1(
                                    precision(
                                            totalTp,
                                            totalFp
                                    ),
                                    recall(
                                            totalTp,
                                            totalFn
                                    )
                            )
                    )
            );

            System.out.println(
                    "Target-self dependency rows excluded = "
                            + targetSelfExclusions.size()
            );

            System.out.println(
                    "Validation warnings = "
                            + warnings.size()
            );

            System.out.println(
                    "Validation ERROR rows = 0"
            );

            System.out.println(
                    "Results: "
                            + out.toAbsolutePath()
            );

        } catch (Throwable t) {

            t.printStackTrace();

            System.exit(1);
        }
    }

    private static Map<String, List<GroundDep>> buildRawScoredGroundTruth(
            List<GroundDep> raw,
            Set<String> authoritativeMethodKeys,
            List<TargetSelfExclusion> targetSelfExclusions) {

        Map<String, List<GroundDep>> out =
                new LinkedHashMap<>();

        for (GroundDep d : raw) {

            String key =
                    d.repo()
                            + ":"
                            + d.method();

            if (!authoritativeMethodKeys.contains(
                    key
            )) {
                continue;
            }

            if (!scoreType(
                    d.kind(),
                    d.owner()
            )) {
                continue;
            }

            if (isTargetSelfDependency(
                    d.kind(),
                    d.owner(),
                    d.name(),
                    d.function()
            )) {

                targetSelfExclusions.add(
                        new TargetSelfExclusion(
                                "REPOSITORY",
                                "",
                                d.repo(),
                                d.method(),
                                d.function(),
                                "",
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.depth(),
                                d.path(),
                                "TARGET_METHOD_SELF_IDENTITY_EXCLUDED_FROM_DEPENDENCY_F1"
                        )
                );

                continue;
            }

            out.computeIfAbsent(
                            key,
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(
                            d
                    );
        }

        return out;
    }

    private static Map<String, List<GenDep>> buildRawScoredGenerated(
            List<GenDep> raw,
            List<TargetSelfExclusion> targetSelfExclusions) {

        Map<String, List<GenDep>> out =
                new LinkedHashMap<>();

        for (GenDep d : raw) {

            if (!scoreType(
                    d.kind(),
                    d.owner()
            )) {
                continue;
            }

            if (harnessOnly(
                    d
            )) {
                continue;
            }

            if (isTargetSelfDependency(
                    d.kind(),
                    d.owner(),
                    d.name(),
                    d.function()
            )) {

                targetSelfExclusions.add(
                        new TargetSelfExclusion(
                                "GENERATED",
                                d.llm(),
                                d.repo(),
                                d.method(),
                                d.function(),
                                d.file(),
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.depth(),
                                d.path(),
                                "TARGET_METHOD_SELF_IDENTITY_EXCLUDED_FROM_DEPENDENCY_F1"
                        )
                );

                continue;
            }

            String key =
                    d.llm()
                            .toLowerCase(
                                    Locale.ROOT
                            )
                            + "|"
                            + normalizeFile(
                            d.file()
                    );

            out.computeIfAbsent(
                            key,
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(
                            d
                    );
        }

        return out;
    }

    private static Map<String, List<GroundDep>> buildGroundTruth(
            List<GroundDep> raw,
            Set<String> authoritativeMethodKeys,
            List<WarningRow> warnings) {

        Map<String, List<GroundDep>> byMethod =
                new TreeMap<>(
                        Comparator
                                .comparingInt(
                                        (String k) ->
                                                Integer.parseInt(
                                                        k.split(":")[0]
                                                )
                                )
                                .thenComparingInt(
                                        k ->
                                                Integer.parseInt(
                                                        k.split(":")[1]
                                                )
                                )
                );

        Set<String> excludedRawMethodKeys =
                new LinkedHashSet<>();

        for (GroundDep d : raw) {

            String methodKey =
                    d.repo()
                            + ":"
                            + d.method();

            if (!authoritativeMethodKeys.contains(
                    methodKey
            )) {

                excludedRawMethodKeys.add(
                        methodKey
                );

                continue;
            }

            if (!scoreType(
                    d.kind(),
                    d.owner()
            )) {

                continue;
            }

            if (isTargetSelfDependency(
                    d.kind(),
                    d.owner(),
                    d.name(),
                    d.function()
            )) {

                continue;
            }

            byMethod
                    .computeIfAbsent(
                            methodKey,
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(
                            d
                    );
        }

        for (String extraKey :
                excludedRawMethodKeys) {

            warnings.add(
                    new WarningRow(
                            "INFO",
                            "",
                            parseRepoFromMethodKey(
                                    extraKey
                            ),
                            parseMethodFromMethodKey(
                                    extraKey
                            ),
                            "",
                            "EXTRA_RAW_GT_METHOD_EXCLUDED",
                            "Raw ground-truth CSV contained method "
                                    + extraKey
                                    + " which is not part of the authoritative manifest."
                    )
            );
        }

        Map<String, List<GroundDep>> out =
                new LinkedHashMap<>();

        for (var entry :
                byMethod.entrySet()) {

            List<GroundDep> candidates =
                    new ArrayList<>(
                            entry.getValue()
                    );

            candidates.sort(
                    Comparator
                            .comparingInt(
                                    GroundDep::depth
                            )
                            .thenComparing(
                                    GroundDep::kind
                            )
                            .thenComparing(
                                    GroundDep::owner
                            )
                            .thenComparing(
                                    GroundDep::name
                            )
                            .thenComparing(
                                    GroundDep::signature
                            )
            );

            List<GroundDep> unique =
                    new ArrayList<>();

            for (GroundDep d :
                    candidates) {

                int mergeIndex =
                        -1;

                for (int i = 0;
                     i < unique.size();
                     i++) {

                    if (sameGroundRequirement(
                            unique.get(i),
                            d
                    )) {

                        mergeIndex =
                                i;

                        break;
                    }
                }

                if (mergeIndex < 0) {

                    unique.add(
                            d
                    );

                } else {

                    GroundDep previous =
                            unique.get(
                                    mergeIndex
                            );

                    if (betterGroundRow(
                            d,
                            previous
                    )) {

                        unique.set(
                                mergeIndex,
                                d
                        );
                    }
                }
            }

            unique.sort(
                    Comparator
                            .comparingInt(
                                    GroundDep::depth
                            )
                            .thenComparing(
                                    GroundDep::kind
                            )
                            .thenComparing(
                                    GroundDep::owner
                            )
                            .thenComparing(
                                    GroundDep::name
                            )
                            .thenComparing(
                                    GroundDep::signature
                            )
            );

            out.put(
                    entry.getKey(),
                    unique
            );
        }

        return out;
    }

    private static boolean sameGroundRequirement(
            GroundDep a,
            GroundDep b) {

        if (!upper(
                a.kind()
        ).equals(
                upper(
                        b.kind()
                )
        )) {

            return false;
        }

        String kind =
                upper(
                        a.kind()
                );

        boolean exactOwner =
                canonicalOwner(
                        a.owner()
                )
                        .equalsIgnoreCase(
                                canonicalOwner(
                                        b.owner()
                                )
                        );

        boolean simpleOwner =
                simpleName(
                        a.owner()
                )
                        .equalsIgnoreCase(
                                simpleName(
                                        b.owner()
                                )
                        );

        if (!exactOwner
                && !simpleOwner) {

            return false;
        }

        if (kind.equals("METHOD")
                || kind.equals("FIELD")) {

            if (!normalizeMember(
                    a.name()
            ).equals(
                    normalizeMember(
                            b.name()
                    )
            )) {

                return false;
            }
        }

        if (kind.equals(
                "CONSTRUCTOR"
        )
                && !constructorMemberCompatible(
                a.name(),
                b.name()
        )) {

            return false;
        }

        if (kind.equals("METHOD")
                || kind.equals("CONSTRUCTOR")) {

            String sa =
                    canonicalSignature(
                            a.signature()
                    );

            String sb =
                    canonicalSignature(
                            b.signature()
                    );

            if (sa.equals(
                    sb
            )) {

                return true;
            }

            boolean fallbackA =
                    fallbackSignature(
                            sa
                    );

            boolean fallbackB =
                    fallbackSignature(
                            sb
                    );

            if (!fallbackA
                    && !fallbackB) {

                return false;
            }

            return arityCompatible(
                    a.signature(),
                    b.signature()
            );
        }

        if (!exactOwner) {

            boolean aQualified =
                    canonicalOwner(
                            a.owner()
                    ).contains(".");

            boolean bQualified =
                    canonicalOwner(
                            b.owner()
                    ).contains(".");

            if (aQualified
                    && bQualified) {

                return false;
            }
        }

        return true;
    }

    private static boolean fallbackSignature(
            String signature) {

        String x =
                normalize(
                        signature
                );

        return x.isBlank()
                || x.matches(
                ".*?/\\d+$"
        )
                || !x.contains("(");
    }

    private static boolean betterGroundRow(
            GroundDep a,
            GroundDep b) {

        if (a.depth()
                != b.depth()) {

            return a.depth()
                    < b.depth();
        }

        if (b.source().isBlank()
                && !a.source().isBlank()) {

            return true;
        }

        if (b.path().isBlank()
                && !a.path().isBlank()) {

            return true;
        }

        return false;
    }

    private static List<GenDep> freshAnalyzeGenerated(
            Path project,
            Path methodsRoot,
            List<Observation> observations,
            Map<String, Models.ManifestRow> manifestByKey,
            Config cfg,
            List<GeneratedAnalysisAuditRow> audit,
            List<WarningRow> warnings)
            throws Exception {

        List<GenDep> out =
                new ArrayList<>();

        Map<String, Path> fileIndex =
                indexGeneratedFiles(
                        methodsRoot
                );

        for (Observation obs :
                observations) {

            if (obs.missing()) {

                audit.add(
                        new GeneratedAnalysisAuditRow(
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "MISSING_RECONSTRUCTION",
                                0,
                                "No generated artifact selected for this authoritative method."
                        )
                );

                continue;
            }

            Models.ManifestRow method =
                    manifestByKey.get(
                            obs.methodKey()
                    );

            if (method == null) {

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "MANIFEST_METHOD_NOT_FOUND",
                                "No authoritative manifest row for "
                                        + obs.methodKey()
                        )
                );

                audit.add(
                        new GeneratedAnalysisAuditRow(
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "MANIFEST_METHOD_NOT_FOUND",
                                0,
                                ""
                        )
                );

                continue;
            }

            String indexKey =
                    obs.llm()
                            .toLowerCase(
                                    Locale.ROOT
                            )
                            + "|"
                            + normalizeFile(
                            obs.file()
                    );

            Path source =
                    fileIndex.get(
                            indexKey
                    );

            if (source == null) {

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "GENERATED_JAVA_FILE_NOT_FOUND",
                                "Selected generated Java file was not found beneath input/methods/"
                                        + obs.llm()
                        )
                );

                audit.add(
                        new GeneratedAnalysisAuditRow(
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "FILE_NOT_FOUND",
                                0,
                                ""
                        )
                );

                continue;
            }

            Models.Artifact artifact =
                    new Models.Artifact();

            artifact.llm =
                    obs.llm();

            artifact.repoFolder =
                    source.getParent() == null
                            ? ""
                            : source
                            .getParent()
                            .getFileName()
                            .toString();

            artifact.filename =
                    source
                            .getFileName()
                            .toString();

            artifact.path =
                    source;

            artifact.mapped =
                    method;

            Models.Closure closure;

            try {

                closure =
                        GeneratedAnalyzer.analyze(
                                artifact,
                                cfg
                        );

            } catch (Throwable t) {

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "FRESH_GENERATED_ANALYSIS_EXCEPTION",
                                t.getClass()
                                        .getSimpleName()
                                        + ": "
                                        + normalize(
                                        t.getMessage()
                                )
                        )
                );

                audit.add(
                        new GeneratedAnalysisAuditRow(
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "EXCEPTION",
                                0,
                                normalize(
                                        t.getMessage()
                                )
                        )
                );

                continue;
            }

            int count =
                    0;

            for (Models.Dep d :
                    closure.all()) {

                count++;

                out.add(
                        new GenDep(
                                obs.llm(),
                                artifact.repoFolder,
                                artifact.filename,

                                obs.repo(),
                                obs.method(),
                                obs.globalMethod(),
                                obs.function(),

                                d.kind.name(),
                                d.owner,
                                d.name,
                                d.signature,
                                d.provenance.name(),

                                d.depth,
                                d.parent,
                                d.path,
                                d.source,
                                d.resolution
                        )
                );
            }

            audit.add(
                    new GeneratedAnalysisAuditRow(
                            obs.llm(),
                            obs.repo(),
                            obs.method(),
                            obs.file(),
                            closure.sourceStatus,
                            count,
                            String.join(
                                    " | ",
                                    closure.diagnostics
                            )
                    )
            );

            if (!"RESOLVED".equalsIgnoreCase(
                    closure.sourceStatus
            )) {

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "FRESH_GENERATED_TARGET_NOT_RESOLVED",
                                "Fresh generated analysis status="
                                        + closure.sourceStatus
                                        + ". This observation is not certified for F1."
                        )
                );

            } else if (count == 0) {

                warnings.add(
                        new WarningRow(
                                "WARN",
                                obs.llm(),
                                obs.repo(),
                                obs.method(),
                                obs.file(),
                                "FRESH_GENERATED_ZERO_DEPENDENCIES",
                                "Target was resolved but its fresh target-rooted closure contains zero dependency rows."
                        )
                );
            }
        }

        return out;
    }

    private static Map<String, Path> indexGeneratedFiles(
            Path methodsRoot)
            throws Exception {

        Map<String, Path> out =
                new LinkedHashMap<>();

        for (String llm :
                LLMS) {

            Path root =
                    methodsRoot.resolve(
                            llm
                    );

            if (!Files.isDirectory(
                    root
            )) {

                continue;
            }

            try (var stream =
                         Files.walk(root)) {

                for (Path p :
                        stream
                                .filter(
                                        Files::isRegularFile
                                )
                                .filter(
                                        x ->
                                                x.toString()
                                                        .toLowerCase(
                                                                Locale.ROOT
                                                        )
                                                        .endsWith(".java")
                                )
                                .toList()) {

                    String key =
                            llm
                                    + "|"
                                    + normalizeFile(
                                    p.getFileName()
                                            .toString()
                            );

                    out.putIfAbsent(
                            key,
                            p
                    );
                }
            }
        }

        return out;
    }

    private static void validateSelectedFileUniqueness(
            List<Observation> observations,
            List<WarningRow> warnings) {

        Map<String, List<Observation>> selected =
                observations.stream()
                        .filter(
                                o -> !o.missing()
                        )
                        .collect(
                                Collectors.groupingBy(
                                        o ->
                                                o.llm()
                                                        + "|"
                                                        + normalizeFile(
                                                        o.file()
                                                ),
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        for (var entry :
                selected.entrySet()) {

            if (entry.getValue().size()
                    <= 1) {

                continue;
            }

            for (Observation o :
                    entry.getValue()) {

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.file(),
                                "SAME_GENERATED_FILE_SELECTED_FOR_MULTIPLE_TARGETS",
                                "Selected generated file is assigned to "
                                        + entry.getValue().size()
                                        + " authoritative observations: "
                                        + entry.getValue()
                                        .stream()
                                        .map(
                                                Observation::methodKey
                                        )
                                        .toList()
                        )
                );
            }
        }
    }

    private static void writeFreshGeneratedCsv(
            Path out,
            List<GenDep> rows)
            throws Exception {

        Path file =
                out.resolve(
                        "FRESH_GENERATED_DEPENDENCIES_RAW.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo_folder,file,repo,method,global_method,function,"
                            + "kind,owner,name,signature,provenance,depth,parent,"
                            + "dependency_path,source,resolution"
            );

            w.newLine();

            for (GenDep d :
                    rows) {

                w.write(
                        csv(
                                d.llm(),
                                d.repoFolder(),
                                d.file(),
                                d.repo(),
                                d.method(),
                                d.globalMethod(),
                                d.function(),
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.provenance(),
                                d.depth(),
                                d.parent(),
                                d.path(),
                                d.source(),
                                d.resolution()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeGeneratedAnalysisAudit(
            Path out,
            List<GeneratedAnalysisAuditRow> rows)
            throws Exception {

        Path file =
                out.resolve(
                        "FRESH_GENERATED_ANALYSIS_AUDIT.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,file,status,dependency_rows,diagnostics"
            );

            w.newLine();

            for (GeneratedAnalysisAuditRow r :
                    rows) {

                w.write(
                        csv(
                                r.llm(),
                                r.repo(),
                                r.method(),
                                r.file(),
                                r.status(),
                                r.dependencyRows(),
                                r.diagnostics()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeGroundTruthRawAudit(
            Path out,
            List<GroundDep> raw,
            Set<String> authoritative)
            throws Exception {

        Path file =
                out.resolve(
                        "GROUND_TRUTH_RECURSIVE_EXTRACTED_ROWS.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "repo,method,global_method,function,"
                            + "scored_final_universe,"
                            + "target_self,"
                            + "kind,owner,name,signature,provenance,depth,parent,"
                            + "dependency_path,source,resolution"
            );

            w.newLine();

            for (GroundDep d :
                    raw) {

                String key =
                        d.repo()
                                + ":"
                                + d.method();

                if (!authoritative.contains(
                        key
                )) {

                    continue;
                }

                boolean targetSelf =
                        isTargetSelfDependency(
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.function()
                        );

                boolean scored =
                        scoreType(
                                d.kind(),
                                d.owner()
                        )
                                && !targetSelf;

                w.write(
                        csv(
                                d.repo(),
                                d.method(),
                                d.globalMethod(),
                                d.function(),
                                scored,
                                targetSelf,
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.provenance(),
                                d.depth(),
                                d.parent(),
                                d.path(),
                                d.source(),
                                d.resolution()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeGroundTruthUniqueAudit(
            Path out,
            Map<String, List<GroundDep>> byMethod)
            throws Exception {

        Path file =
                out.resolve(
                        "GROUND_TRUTH_UNIQUE_SCORED_IDENTITIES.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "repo,method,global_method,function,kind,owner,name,signature,"
                            + "provenance,depth,parent,dependency_path,source,resolution"
            );

            w.newLine();

            for (List<GroundDep> rows :
                    byMethod.values()) {

                for (GroundDep d :
                        rows) {

                    w.write(
                            csv(
                                    d.repo(),
                                    d.method(),
                                    d.globalMethod(),
                                    d.function(),
                                    d.kind(),
                                    d.owner(),
                                    d.name(),
                                    d.signature(),
                                    d.provenance(),
                                    d.depth(),
                                    d.parent(),
                                    d.path(),
                                    d.source(),
                                    d.resolution()
                            )
                    );

                    w.newLine();
                }
            }
        }
    }

    private static void writeGeneratedUniqueAudit(
            Path out,
            Map<String, List<GenDep>> byFile)
            throws Exception {

        Path file =
                out.resolve(
                        "GENERATED_UNIQUE_SCORED_IDENTITIES.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo_folder,file,repo,method,global_method,function,"
                            + "kind,owner,name,signature,provenance,depth,parent,"
                            + "dependency_path,source,resolution"
            );

            w.newLine();

            for (List<GenDep> rows :
                    byFile.values()) {

                for (GenDep d :
                        rows) {

                    w.write(
                            csv(
                                    d.llm(),
                                    d.repoFolder(),
                                    d.file(),
                                    d.repo(),
                                    d.method(),
                                    d.globalMethod(),
                                    d.function(),
                                    d.kind(),
                                    d.owner(),
                                    d.name(),
                                    d.signature(),
                                    d.provenance(),
                                    d.depth(),
                                    d.parent(),
                                    d.path(),
                                    d.source(),
                                    d.resolution()
                            )
                    );

                    w.newLine();
                }
            }
        }
    }

    private static void writeTargetSelfExclusions(
            Path out,
            List<TargetSelfExclusion> rows)
            throws Exception {

        Path file =
                out.resolve(
                        "TARGET_SELF_DEPENDENCIES_EXCLUDED.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "side,llm,repo,method,function,file,kind,owner,name,signature,"
                            + "depth,dependency_path,reason"
            );

            w.newLine();

            for (TargetSelfExclusion r :
                    rows) {

                w.write(
                        csv(
                                r.side(),
                                r.llm(),
                                r.repo(),
                                r.method(),
                                r.function(),
                                r.file(),
                                r.kind(),
                                r.owner(),
                                r.name(),
                                r.signature(),
                                r.depth(),
                                r.path(),
                                r.reason()
                        )
                );

                w.newLine();
            }
        }
    }

    private static Map<String, List<GenDep>> buildGenerated(
            List<GenDep> raw,
            List<WarningRow> warnings) {

        Map<String, LinkedHashMap<String, GenDep>> temp =
                new LinkedHashMap<>();

        for (GenDep d :
                raw) {

            if (!scoreType(
                    d.kind(),
                    d.owner()
            )) {

                continue;
            }

            if (harnessOnly(
                    d
            )) {

                continue;
            }

            if (isTargetSelfDependency(
                    d.kind(),
                    d.owner(),
                    d.name(),
                    d.function()
            )) {

                continue;
            }

            String key =
                    d.llm()
                            .toLowerCase(
                                    Locale.ROOT
                            )
                            + "|"
                            + normalizeFile(
                            d.file()
                    );

            String identity =
                    strictGeneratedIdentity(
                            d
                    );

            LinkedHashMap<String, GenDep> map =
                    temp.computeIfAbsent(
                            key,
                            ignored ->
                                    new LinkedHashMap<>()
                    );

            GenDep previous =
                    map.get(
                            identity
                    );

            if (previous == null
                    || betterGeneratedRow(
                    d,
                    previous
            )) {

                map.put(
                        identity,
                        d
                );
            }
        }

        Map<String, List<GenDep>> out =
                new LinkedHashMap<>();

        for (var entry :
                temp.entrySet()) {

            List<GenDep> rows =
                    new ArrayList<>(
                            entry
                                    .getValue()
                                    .values()
                    );

            rows.sort(
                    Comparator
                            .comparingInt(
                                    GenDep::depth
                            )
                            .thenComparing(
                                    GenDep::kind
                            )
                            .thenComparing(
                                    GenDep::owner
                            )
                            .thenComparing(
                                    GenDep::name
                            )
                            .thenComparing(
                                    GenDep::signature
                            )
            );

            out.put(
                    entry.getKey(),
                    rows
            );
        }

        return out;
    }

    private static boolean betterGeneratedRow(
            GenDep a,
            GenDep b) {

        if (a.depth()
                != b.depth()) {

            return a.depth()
                    < b.depth();
        }

        if (b.source().isBlank()
                && !a.source().isBlank()) {

            return true;
        }

        if (b.path().isBlank()
                && !a.path().isBlank()) {

            return true;
        }

        return false;
    }

    private static boolean harnessOnly(
            GenDep d) {

        String path =
                normalize(
                        d.path()
                )
                        .toLowerCase(
                                Locale.ROOT
                        );

        String parent =
                normalize(
                        d.parent()
                )
                        .toLowerCase(
                                Locale.ROOT
                        );

        String source =
                normalize(
                        d.source()
                )
                        .toLowerCase(
                                Locale.ROOT
                        );

        return path.startsWith(
                "main ->"
        )
                || path.startsWith(
                "main() ->"
        )
                || path.startsWith(
                "<main> ->"
        )
                || path.contains(
                "execution-harness-only"
        )
                || parent.equals(
                "main"
        )
                || parent.equals(
                "main()"
        )
                || source.contains(
                "execution harness only"
        );
    }

    private static void loadVerifiedMappingOverrides(
            Path csv,
            Map<String, Models.ManifestRow> manifestByKey,
            List<WarningRow> warnings)
            throws Exception {

        if (!Files.isRegularFile(csv)) {
            warnings.add(
                    new WarningRow(
                            "ERROR",
                            "",
                            0,
                            0,
                            csv.toString(),
                            "VERIFIED_MAPPING_OVERRIDE_CSV_NOT_FOUND",
                            "Required verified override CSV was not found. "
                                    + "Expected: " + csv.toAbsolutePath()
                    )
            );
            return;
        }

        try (BufferedReader reader =
                     Files.newBufferedReader(
                             csv,
                             StandardCharsets.UTF_8
                     )) {

            String headerLine = reader.readLine();

            if (headerLine == null || headerLine.isBlank()) {
                warnings.add(
                        new WarningRow(
                                "ERROR",
                                "",
                                0,
                                0,
                                csv.toString(),
                                "VERIFIED_MAPPING_OVERRIDE_CSV_EMPTY",
                                "Verified override CSV has no header/data."
                        )
                );
                return;
            }

            List<String> headers = parseCsvLine(headerLine);

            Map<String, Integer> index = new LinkedHashMap<>();

            for (int i = 0; i < headers.size(); i++) {
                index.put(
                        headers.get(i).trim().toLowerCase(Locale.ROOT),
                        i
                );
            }

            for (String required :
                    List.of(
                            "llm",
                            "generated_file",
                            "authoritative_method_id"
                    )) {

                if (!index.containsKey(required)) {
                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    "",
                                    0,
                                    0,
                                    csv.toString(),
                                    "VERIFIED_MAPPING_OVERRIDE_CSV_BAD_HEADER",
                                    "Missing required CSV column: " + required
                            )
                    );
                    return;
                }
            }

            String line;

            int loaded = 0;

            while ((line = reader.readLine()) != null) {

                if (line.isBlank()) {
                    continue;
                }

                List<String> values = parseCsvLine(line);

                String llm =
                        csvValue(values, index, "llm")
                                .trim()
                                .toLowerCase(Locale.ROOT);

                String generatedFile =
                        normalizeFile(
                                csvValue(
                                        values,
                                        index,
                                        "generated_file"
                                )
                        );

                String targetKey =
                        csvValue(
                                values,
                                index,
                                "authoritative_method_id"
                        ).trim();

                String reason =
                        csvValue(
                                values,
                                index,
                                "reason"
                        ).trim();

                if (llm.isBlank()
                        || generatedFile.isBlank()
                        || targetKey.isBlank()) {

                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    llm,
                                    0,
                                    0,
                                    generatedFile,
                                    "VERIFIED_MAPPING_OVERRIDE_ROW_INCOMPLETE",
                                    "Override row is missing llm, generated_file or "
                                            + "authoritative_method_id."
                            )
                    );
                    continue;
                }

                Models.ManifestRow target =
                        manifestByKey.get(targetKey);

                if (target == null) {

                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    llm,
                                    0,
                                    0,
                                    generatedFile,
                                    "VERIFIED_MAPPING_OVERRIDE_TARGET_NOT_IN_MANIFEST",
                                    "Override target " + targetKey
                                            + " does not exist in authoritative manifest."
                            )
                    );
                    continue;
                }

                String lookupKey =
                        llm
                                + "|"
                                + generatedFile.toLowerCase(Locale.ROOT);

                String previous =
                        VERIFIED_MAPPING_OVERRIDES.put(
                                lookupKey,
                                targetKey
                        );

                loaded++;

                warnings.add(
                        new WarningRow(
                                "INFO",
                                llm,
                                target.repoIndex(),
                                target.methodNumber(),
                                generatedFile,
                                "VERIFIED_MAPPING_OVERRIDE_LOADED",
                                "Loaded " + lookupKey
                                        + " -> " + targetKey
                                        + (reason.isBlank()
                                        ? ""
                                        : " | " + reason)
                                        + (previous == null
                                        ? ""
                                        : " | replaced previous=" + previous)
                        )
                );
            }

            if (loaded == 0) {
                warnings.add(
                        new WarningRow(
                                "ERROR",
                                "",
                                0,
                                0,
                                csv.toString(),
                                "VERIFIED_MAPPING_OVERRIDE_NONE_LOADED",
                                "No verified mapping overrides were loaded."
                        )
                );
            }
        }
    }

    private static List<String> parseCsvLine(
            String line) {

        List<String> values =
                new ArrayList<>();

        StringBuilder current =
                new StringBuilder();

        boolean quoted =
                false;

        for (int i = 0;
             i < line.length();
             i++) {

            char c =
                    line.charAt(i);

            if (c == '"') {

                if (quoted
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '"') {

                    current.append('"');
                    i++;

                } else {

                    quoted =
                            !quoted;
                }

            } else if (c == ','
                    && !quoted) {

                values.add(
                        current.toString()
                );

                current.setLength(
                        0
                );

            } else {

                current.append(
                        c
                );
            }
        }

        values.add(
                current.toString()
        );

        return values;
    }

    private static String csvValue(
            List<String> values,
            Map<String, Integer> index,
            String column) {

        Integer i =
                index.get(
                        column.toLowerCase(Locale.ROOT)
                );

        if (i == null
                || i < 0
                || i >= values.size()) {
            return "";
        }

        return values.get(i);
    }

    private static List<MappingRow> repairWeakMappings(
            Path methodsRoot,
            List<MappingRow> mappings,
            List<Models.ManifestRow> manifest,
            List<WarningRow> warnings)
            throws Exception {

        Map<String, Path> files =
                indexGeneratedFiles(
                        methodsRoot
                );

        Map<String, Models.ManifestRow> manifestByKey =
                manifest.stream()
                        .collect(
                                Collectors.toMap(
                                        Models.ManifestRow::key,
                                        x -> x,
                                        (a, b) -> a,
                                        LinkedHashMap::new
                                )
                        );

        List<MappingRow> out =
                new ArrayList<>();

        for (MappingRow row :
                mappings) {

            String overrideLookupKey =
                    row.llm()
                            .toLowerCase(
                                    Locale.ROOT
                            )
                            + "|"
                            + normalizeFile(
                            row.file()
                    );

            String overrideTargetKey =
                    VERIFIED_MAPPING_OVERRIDES.get(
                            overrideLookupKey
                    );

            if (overrideTargetKey != null) {

                Models.ManifestRow target =
                        manifestByKey.get(
                                overrideTargetKey
                        );

                if (target == null) {

                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    row.llm(),
                                    row.repo(),
                                    row.method(),
                                    row.file(),
                                    "VERIFIED_MAPPING_OVERRIDE_TARGET_NOT_IN_MANIFEST",
                                    "Override "
                                            + overrideLookupKey
                                            + " -> "
                                            + overrideTargetKey
                                            + " but the authoritative manifest does not contain "
                                            + overrideTargetKey
                            )
                    );

                    out.add(
                            row
                    );

                    continue;
                }

                MappingRow repaired =
                        new MappingRow(
                                row.llm(),
                                target.repoIndex(),
                                target.methodNumber(),
                                target.globalNumber(),
                                target.functionName(),
                                row.file(),
                                "VERIFIED_EXACT_TARGET_OVERRIDE",
                                row.targetPreservation()
                        );

                out.add(
                        repaired
                );

                warnings.add(
                        new WarningRow(
                                "INFO",
                                row.llm(),
                                target.repoIndex(),
                                target.methodNumber(),
                                row.file(),
                                "VERIFIED_MAPPING_OVERRIDE_APPLIED",
                                "Verified mapping override applied: original="
                                        + row.repo()
                                        + ":"
                                        + row.method()
                                        + " "
                                        + row.function()
                                        + " -> authoritative="
                                        + target.key()
                                        + " "
                                        + target.functionName()
                        )
                );

                continue;
            }

            if (!upper(
                    row.mappingStatus()
            ).equals(
                    "FILENAME_HINT_ONLY"
            )) {

                out.add(
                        row
                );

                continue;
            }

            Path source =
                    files.get(
                            row.llm()
                                    .toLowerCase(
                                            Locale.ROOT
                                    )
                                    + "|"
                                    + normalizeFile(
                                    row.file()
                            )
                    );

            if (source == null) {

                out.add(
                        row
                );

                warnings.add(
                        new WarningRow(
                                "WARN",
                                row.llm(),
                                row.repo(),
                                row.method(),
                                row.file(),
                                "WEAK_MAPPING_FILE_NOT_FOUND_FOR_REPAIR",
                                "Filename-only mapping could not be content-audited because the Java file was not found."
                        )
                );

                continue;
            }

            String text =
                    Files.readString(
                            source,
                            StandardCharsets.UTF_8
                    );

            String marker =
                    markerBody(
                            text
                    );

            String search =
                    marker.isBlank()
                            ? text
                            : marker;

            int filenameNumber =
                    filenameMethodNumber(
                            row.file()
                    );

            Models.ManifestRow old =
                    manifest.stream()
                            .filter(
                                    m ->
                                            m.repoIndex()
                                                    == row.repo()
                                                    && m.methodNumber()
                                                    == row.method()
                            )
                            .findFirst()
                            .orElse(null);

            if (old != null
                    && containsMethodName(
                    search,
                    old.methodName()
            )
                    && declaringClassEvidencePresent(
                    text,
                    old
            )) {

                out.add(
                        row
                );

                continue;
            }

            List<MappingCandidate> candidates =
                    new ArrayList<>();

            for (Models.ManifestRow m :
                    manifest) {

                if (!containsMethodName(
                        search,
                        m.methodName()
                )) {

                    continue;
                }

                int score =
                        marker.isBlank()
                                ? 40
                                : 100;

                if (declaringClassEvidencePresent(
                        text,
                        m
                )) {

                    score +=
                            100;
                }

                if (filenameNumber > 0
                        && filenameNumber
                        == m.methodNumber()) {

                    score +=
                            25;
                }

                if (repoNameCompatible(
                        row.file(),
                        m.repoSlug()
                )) {

                    score +=
                            45;
                }

                candidates.add(
                        new MappingCandidate(
                                m,
                                score
                        )
                );
            }

            candidates.sort(
                    Comparator
                            .comparingInt(
                                    MappingCandidate::score
                            )
                            .reversed()
                            .thenComparingInt(
                                    x ->
                                            x.method()
                                                    .repoIndex()
                            )
                            .thenComparingInt(
                                    x ->
                                            x.method()
                                                    .methodNumber()
                            )
            );

            if (candidates.isEmpty()) {

                out.add(
                        row
                );

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                row.llm(),
                                row.repo(),
                                row.method(),
                                row.file(),
                                "WEAK_MAPPING_TARGET_NAME_ABSENT",
                                "Filename-only mapped target method identity was absent from the generated file and no authoritative manifest candidate could be inferred."
                        )
                );

                continue;
            }

            MappingCandidate best =
                    candidates.get(0);

            boolean tied =
                    candidates.size() > 1
                            && candidates.get(1)
                            .score()
                            == best.score();

            if (tied) {

                out.add(
                        row
                );

                warnings.add(
                        new WarningRow(
                                "ERROR",
                                row.llm(),
                                row.repo(),
                                row.method(),
                                row.file(),
                                "WEAK_MAPPING_REPAIR_AMBIGUOUS",
                                "Filename-only mapping is inconsistent with file content and the best content-based remap is tied: "
                                        + candidates.stream()
                                        .limit(6)
                                        .map(
                                                x ->
                                                        x.method()
                                                                .key()
                                                                + "="
                                                                + x.method()
                                                                .functionName()
                                                                + " score="
                                                                + x.score()
                                        )
                                        .toList()
                        )
                );

                continue;
            }

            Models.ManifestRow target =
                    best.method();

            MappingRow repaired =
                    new MappingRow(
                            row.llm(),
                            target.repoIndex(),
                            target.methodNumber(),
                            target.globalNumber(),
                            target.functionName(),
                            row.file(),
                            "CONTENT_REPAIRED_FROM_FILENAME_HINT",
                            row.targetPreservation()
                    );

            out.add(
                    repaired
            );

            warnings.add(
                    new WarningRow(
                            "INFO",
                            row.llm(),
                            target.repoIndex(),
                            target.methodNumber(),
                            row.file(),
                            "WEAK_MAPPING_CONTENT_REPAIRED",
                            "Original weak mapping "
                                    + row.repo()
                                    + ":"
                                    + row.method()
                                    + " "
                                    + row.function()
                                    + " was inconsistent with generated file content. Repaired to "
                                    + target.key()
                                    + " "
                                    + target.functionName()
                                    + " (score="
                                    + best.score()
                                    + ")."
                    )
            );
        }

        return out;
    }

    private static boolean declaringClassEvidencePresent(
            String text,
            Models.ManifestRow method) {

        String targetClass =
                simpleName(
                        method.className()
                );

        if (targetClass.isBlank()) {

            return false;
        }

        if (Pattern.compile(
                "\\b(class|interface|enum|record)\\s+"
                        + Pattern.quote(
                        targetClass
                )
                        + "\\b"
        )
                .matcher(
                        text
                )
                .find()) {

            return true;
        }

        String combined =
                targetClass
                        + "."
                        + method.methodName();

        return text.contains(
                combined
        );
    }

    private static String markerBody(
            String text) {

        Matcher matcher =
                Pattern.compile(
                        "(?is)ORIGINAL\\s+CODESEARCHNET\\s+METHOD\\s*[—-]+\\s*START(.*?)"
                                + "ORIGINAL\\s+CODESEARCHNET\\s+METHOD\\s*[—-]+\\s*END"
                )
                        .matcher(
                                text
                        );

        return matcher.find()
                ? matcher.group(1)
                : "";
    }

    private static boolean containsMethodName(
            String text,
            String methodName) {

        if (text == null
                || methodName == null
                || methodName.isBlank()) {

            return false;
        }

        return Pattern.compile(
                "\\b"
                        + Pattern.quote(
                        methodName
                )
                        + "\\s*\\("
        )
                .matcher(
                        text
                )
                .find();
    }

    private static boolean repoNameCompatible(
            String file,
            String repoSlug) {

        String f =
                normalizeFile(
                        file
                )
                        .replaceAll(
                                "[^a-z0-9]",
                                ""
                        );

        String slug =
                normalize(
                        repoSlug
                )
                        .toLowerCase(
                                Locale.ROOT
                        );

        String last =
                slug.contains("/")
                        ? slug.substring(
                        slug.lastIndexOf('/')
                                + 1
                )
                        : slug;

        String r =
                last.replaceAll(
                        "[^a-z0-9]",
                        ""
                );

        if (r.length() >= 4
                && f.contains(
                r
        )) {

            return true;
        }

        for (String token :
                last.split(
                        "[-_.]"
                )) {

            String t =
                    token.replaceAll(
                            "[^a-z0-9]",
                            ""
                    );

            if (t.length() >= 4
                    && f.contains(
                    t
            )) {

                return true;
            }
        }

        return false;
    }

    private static List<Observation> buildObservations(
            Map<String, List<GroundDep>> gtByMethod,
            List<MappingRow> mappings,
            List<MappingAuditRow> audit,
            List<WarningRow> warnings) {

        Map<String, List<MappingRow>> grouped =
                mappings.stream()
                        .collect(
                                Collectors.groupingBy(
                                        MappingRow::observationKey,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        List<String> methods =
                new ArrayList<>(
                        gtByMethod.keySet()
                );

        methods.sort(
                Comparator
                        .comparingInt(
                                (String k) ->
                                        Integer.parseInt(
                                                k.split(":")[0]
                                        )
                        )
                        .thenComparingInt(
                                k ->
                                        Integer.parseInt(
                                                k.split(":")[1]
                                        )
                        )
        );

        List<Observation> out =
                new ArrayList<>();

        for (String methodKey :
                methods) {

            List<GroundDep> gtRows =
                    gtByMethod.get(
                            methodKey
                    );

            GroundDep metadata =
                    gtRows.get(0);

            int repo =
                    metadata.repo();

            int method =
                    metadata.method();

            int globalMethod =
                    metadata.globalMethod();

            String function =
                    metadata.function();

            for (String llm :
                    LLMS) {

                String key =
                        llm
                                + "|"
                                + repo
                                + ":"
                                + method;

                List<MappingRow> candidates =
                        grouped.getOrDefault(
                                key,
                                List.of()
                        );

                if (candidates.isEmpty()) {

                    out.add(
                            new Observation(
                                    llm,
                                    repo,
                                    method,
                                    globalMethod,
                                    function,
                                    "<MISSING>",
                                    "MISSING_RECONSTRUCTION",
                                    "NO_GENERATED_FILE_MAPPED",
                                    "NOT_AVAILABLE",
                                    true
                            )
                    );

                    audit.add(
                            new MappingAuditRow(
                                    llm,
                                    repo,
                                    method,
                                    "<MISSING>",
                                    "MISSING",
                                    "No generated artifact maps to this authoritative method."
                            )
                    );

                    warnings.add(
                            new WarningRow(
                                    "INFO",
                                    llm,
                                    repo,
                                    method,
                                    "<MISSING>",
                                    "MISSING_RECONSTRUCTION",
                                    "No generated file maps to the authoritative method; empty generated set will be scored."
                            )
                    );

                    continue;
                }

                MappingRow selected;

                String selectionReason;

                if (candidates.size() == 1) {

                    selected =
                            candidates.get(0);

                    selectionReason =
                            "UNIQUE_MAPPING";

                } else {

                    List<MappingRow> verified =
                            candidates.stream()
                                    .filter(
                                            m ->
                                                    upper(
                                                            m.mappingStatus()
                                                    )
                                                            .equals(
                                                                    "VERIFIED_EXACT_TARGET_OVERRIDE"
                                                            )
                                    )
                                    .toList();

                    if (verified.size() == 1) {

                        selected =
                                verified.get(0);

                        selectionReason =
                                "DUPLICATE_RESOLVED_BY_VERIFIED_TARGET_OVERRIDE";

                    } else {

                        List<MappingRow> intendedFilenameMatches =
                                candidates.stream()
                                        .filter(
                                                m ->
                                                        filenameMethodNumber(
                                                                m.file()
                                                        )
                                                                == method
                                        )
                                        .toList();

                        if (intendedFilenameMatches.size()
                                == 1) {

                            selected =
                                    intendedFilenameMatches.get(0);

                            selectionReason =
                                    "DUPLICATE_RESOLVED_BY_INTENDED_TARGET_FILENAME";

                        } else {

                            int bestStrength =
                                    candidates.stream()
                                            .mapToInt(
                                                    m ->
                                                            mappingStrength(
                                                                    m.mappingStatus()
                                                            )
                                            )
                                            .max()
                                            .orElse(0);

                            List<MappingRow> strongest =
                                    candidates.stream()
                                            .filter(
                                                    m ->
                                                            mappingStrength(
                                                                    m.mappingStatus()
                                                            )
                                                                    == bestStrength
                                            )
                                            .toList();

                            if (strongest.size()
                                    == 1) {

                                selected =
                                        strongest.get(0);

                                selectionReason =
                                        "DUPLICATE_RESOLVED_BY_MAPPING_EVIDENCE_PRIORITY";

                            } else {

                                selected =
                                        strongest.stream()
                                                .sorted(
                                                        Comparator.comparing(
                                                                MappingRow::file
                                                        )
                                                )
                                                .findFirst()
                                                .orElseThrow();

                                selectionReason =
                                        "AMBIGUOUS_DUPLICATE_AFTER_FILENAME_AND_EVIDENCE";

                                warnings.add(
                                        new WarningRow(
                                                "ERROR",
                                                llm,
                                                repo,
                                                method,
                                                selected.file(),
                                                "AMBIGUOUS_DUPLICATE_MAPPING",
                                                "Multiple generated artifacts remain tied after verified override, intended-filename and mapping-evidence checks."
                                        )
                                );
                            }
                        }
                    }

                    for (MappingRow candidate :
                            candidates) {

                        audit.add(
                                new MappingAuditRow(
                                        llm,
                                        repo,
                                        method,
                                        candidate.file(),
                                        candidate.file()
                                                .equals(
                                                        selected.file()
                                                )
                                                ? "SELECTED"
                                                : "DUPLICATE_EXCLUDED",
                                        selectionReason
                                                + " | mapping_status="
                                                + candidate.mappingStatus()
                                                + " | strength="
                                                + mappingStrength(
                                                candidate.mappingStatus()
                                        )
                                )
                        );
                    }
                }

                if (candidates.size()
                        == 1) {

                    audit.add(
                            new MappingAuditRow(
                                    llm,
                                    repo,
                                    method,
                                    selected.file(),
                                    "SELECTED",
                                    selectionReason
                                            + " | mapping_status="
                                            + selected.mappingStatus()
                            )
                    );
                }

                if (!function.isBlank()
                        && !selected.function().isBlank()
                        && !function.equals(
                        selected.function()
                )) {

                    warnings.add(
                            new WarningRow(
                                    "WARN",
                                    llm,
                                    repo,
                                    method,
                                    selected.file(),
                                    "FUNCTION_METADATA_DIFFERENCE",
                                    "GT function="
                                            + function
                                            + " | mapping function="
                                            + selected.function()
                            )
                    );
                }

                out.add(
                        new Observation(
                                llm,
                                repo,
                                method,

                                globalMethod > 0
                                        ? globalMethod
                                        : selected.globalMethod(),

                                function.isBlank()
                                        ? selected.function()
                                        : function,

                                selected.file(),
                                selected.mappingStatus(),
                                selectionReason,
                                selected.targetPreservation(),
                                false
                        )
                );
            }
        }

        out.sort(
                Comparator
                        .comparing(
                                Observation::llm
                        )
                        .thenComparingInt(
                                Observation::repo
                        )
                        .thenComparingInt(
                                Observation::method
                        )
        );

        return out;
    }

    private static int mappingStrength(
            String status) {

        String s =
                upper(
                        status
                );

        return switch (s) {

            case "VERIFIED_EXACT_TARGET_OVERRIDE" ->
                    120;

            case "MARKER_DECLARATION_CONFIRMED" ->
                    110;

            case "CONTENT_REPAIRED_FROM_FILENAME_HINT" ->
                    105;

            case "MARKER_NAME_CLASS_CONFIRMED" ->
                    100;

            case "REPOSITORY_CONTENT_REMAPPED" ->
                    95;

            case "AST_NAME_CONFIRMED" ->
                    90;

            case "GLOBAL_ONE_TO_ONE" ->
                    80;

            case "FILENAME_HINT_ONLY" ->
                    20;

            default ->
                    40;
        };
    }

    private static int filenameMethodNumber(
            String file) {

        Matcher matcher =
                Pattern.compile(
                        "(?i)(?:^|[/\\\\])method[_-]?(\\d+)"
                )
                        .matcher(
                                normalize(
                                        file
                                )
                        );

        if (!matcher.find()) {

            matcher =
                    Pattern.compile(
                            "(?i)^method[_-]?(\\d+)"
                    )
                            .matcher(
                                    normalizeFile(
                                            file
                                    )
                            );
        }

        return matcher.find()
                ? integer(
                matcher.group(1)
        )
                : -1;
    }

    private static boolean isTargetSelfDependency(
            String kind,
            String owner,
            String name,
            String function) {

        if (!upper(
                kind
        ).equals(
                "METHOD"
        )) {

            return false;
        }

        String functionText =
                normalize(
                        function
                );

        if (functionText.isBlank()) {

            return false;
        }

        int lastDot =
                functionText.lastIndexOf('.');

        if (lastDot <= 0
                || lastDot
                >= functionText.length() - 1) {

            return false;
        }

        String targetOwner =
                functionText.substring(
                        0,
                        lastDot
                );

        String targetMethod =
                functionText.substring(
                        lastDot + 1
                );

        int paren =
                targetMethod.indexOf('(');

        if (paren >= 0) {

            targetMethod =
                    targetMethod.substring(
                            0,
                            paren
                    );
        }

        if (!normalizeMember(
                name
        ).equals(
                normalizeMember(
                        targetMethod
                )
        )) {

            return false;
        }

        String expectedOwnerSimple =
                simpleName(
                        targetOwner
                );

        String actualOwnerSimple =
                simpleName(
                        owner
                );

        return !expectedOwnerSimple.isBlank()
                && expectedOwnerSimple
                .equalsIgnoreCase(
                        actualOwnerSimple
                );
    }

    private static void validateNoTargetSelfDependencies(
            Map<String, List<GroundDep>> gtByMethod,
            Map<String, List<GenDep>> genByFile,
            List<WarningRow> warnings) {

        for (List<GroundDep> rows :
                gtByMethod.values()) {

            for (GroundDep d :
                    rows) {

                if (isTargetSelfDependency(
                        d.kind(),
                        d.owner(),
                        d.name(),
                        d.function()
                )) {

                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    "",
                                    d.repo(),
                                    d.method(),
                                    "",
                                    "TARGET_SELF_DEPENDENCY_SURVIVED_GT_SCORING",
                                    depLabel(
                                            d
                                    )
                    )
                    );
                }
            }
        }

        for (List<GenDep> rows :
                genByFile.values()) {

            for (GenDep d :
                    rows) {

                if (isTargetSelfDependency(
                        d.kind(),
                        d.owner(),
                        d.name(),
                        d.function()
                )) {

                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    d.llm(),
                                    d.repo(),
                                    d.method(),
                                    d.file(),
                                    "TARGET_SELF_DEPENDENCY_SURVIVED_GENERATED_SCORING",
                                    depLabel(
                                            d
                                    )
                    )
                    );
                }
            }
        }
    }

    private static MatchOutput matchOneToOne(
            Observation obs,
            List<GroundDep> expected,
            List<GenDep> actual,
            List<WarningRow> warnings) {

        if (obs.missing()) {

            List<Decision> decisions =
                    expected.stream()
                            .map(
                                    gt ->
                                            new Decision(
                                                    obs,
                                                    gt,
                                                    null,
                                                    "MISSING_RECONSTRUCTION",
                                                    "FN",
                                                    false,
                                                    false,
                                                    "No generated reconstruction is mapped to this authoritative target."
                                            )
                            )
                            .toList();

            return new MatchOutput(
                    decisions,
                    List.of()
            );
        }

        Map<String, String> structuralAliases =
                inferStructuralOwnerAliases(
                        obs,
                        expected,
                        actual,
                        warnings
                );

        List<Candidate> candidates =
                new ArrayList<>();

        for (int i = 0;
             i < expected.size();
             i++) {

            for (int j = 0;
                 j < actual.size();
                 j++) {

                Candidate candidate =
                        candidate(
                                obs,
                                expected.get(i),
                                actual.get(j),
                                i,
                                j,
                                structuralAliases
                        );

                if (candidate != null) {

                    candidates.add(
                            candidate
                    );
                }
            }
        }

        candidates.sort(
                Comparator
                        .comparingInt(
                                Candidate::score
                        )
                        .reversed()
                        .thenComparingInt(
                                Candidate::expectedIndex
                        )
                        .thenComparingInt(
                                Candidate::generatedIndex
                        )
        );

        boolean[] usedExpected =
                new boolean[
                        expected.size()
                        ];

        boolean[] usedGenerated =
                new boolean[
                        actual.size()
                        ];

        Map<Integer, Candidate> selectedByExpected =
                new HashMap<>();

        for (Candidate candidate :
                candidates) {

            if (usedExpected[
                    candidate.expectedIndex()
                    ]
                    || usedGenerated[
                    candidate.generatedIndex()
                    ]) {

                continue;
            }

            usedExpected[
                    candidate.expectedIndex()
                    ] =
                    true;

            usedGenerated[
                    candidate.generatedIndex()
                    ] =
                    true;

            selectedByExpected.put(
                    candidate.expectedIndex(),
                    candidate
            );
        }

        List<Decision> decisions =
                new ArrayList<>();

        for (int i = 0;
             i < expected.size();
             i++) {

            GroundDep gt =
                    expected.get(i);

            Candidate selected =
                    selectedByExpected.get(
                            i
                    );

            if (selected == null) {

                decisions.add(
                        new Decision(
                                obs,
                                gt,
                                null,
                                "MISSING",
                                "FN",
                                false,
                                false,
                                "No compatible target-rooted generated dependency found."
                        )
                );

            } else {

                GenDep generated =
                        actual.get(
                                selected.generatedIndex()
                        );

                boolean provenanceCorrect =
                        provenanceCompatible(
                                gt.provenance(),
                                generated.provenance()
                        );

                decisions.add(
                        new Decision(
                                obs,
                                gt,
                                generated,
                                selected.matchType(),
                                "TP",
                                true,
                                provenanceCorrect,
                                "One-to-one clean match: kind + owner/entity + member + arity/signature compatibility."
                        )
                );
            }
        }

        List<Extra> extras =
                new ArrayList<>();

        for (int j = 0;
             j < actual.size();
             j++) {

            if (!usedGenerated[j]) {

                extras.add(
                        new Extra(
                                obs,
                                actual.get(j),
                                "FP",
                                "Target-rooted generated dependency was not matched one-to-one to any repository requirement."
                        )
                );
            }
        }

        List<Decision> fns =
                decisions.stream()
                        .filter(
                                d -> !d.tp()
                        )
                        .toList();

        for (Decision fn :
                fns) {

            for (Extra fp :
                    extras) {

                if (obviousEquivalent(
                        obs,
                        fn.expected(),
                        fp.generated(),
                        structuralAliases
                )) {

                    warnings.add(
                            new WarningRow(
                                    "ERROR",
                                    obs.llm(),
                                    obs.repo(),
                                    obs.method(),
                                    obs.file(),
                                    "OBVIOUS_EQUIVALENT_LEFT_UNMATCHED",
                                    "GT="
                                            + depLabel(
                                            fn.expected()
                                    )
                                            + " | GENERATED="
                                            + depLabel(
                                            fp.generated()
                                    )
                            )
                    );
                }
            }
        }

        return new MatchOutput(
                decisions,
                extras
        );
    }

    private static Candidate candidate(
            Observation obs,
            GroundDep gt,
            GenDep generated,
            int expectedIndex,
            int generatedIndex,
            Map<String, String> structuralAliases) {

        String kind =
                upper(
                        gt.kind()
                );

        if (!kind.equals(
                upper(
                        generated.kind()
                )
        )) {

            return null;
        }

        OwnerMatch owner =
                ownerMatch(
                        obs,
                        gt.owner(),
                        generated.owner(),
                        structuralAliases
                );

        if (!owner.compatible()) {

            return null;
        }

        int score =
                owner.score();

        if (kind.equals("METHOD")
                || kind.equals("FIELD")) {

            if (!normalizeMember(
                    gt.name()
            ).equals(
                    normalizeMember(
                            generated.name()
                    )
            )) {

                return null;
            }

            score +=
                    40;

        } else if (kind.equals(
                "CONSTRUCTOR"
        )) {

            if (!constructorMemberCompatible(
                    gt.name(),
                    generated.name()
            )) {

                return null;
            }

            score +=
                    30;
        }

        if (kind.equals("METHOD")
                || kind.equals("CONSTRUCTOR")) {

            if (!arityCompatible(
                    gt.signature(),
                    generated.signature()
            )) {

                return null;
            }

            if (arity(
                    gt.signature()
            ) >= 0
                    && arity(
                    generated.signature()
            ) >= 0) {

                score +=
                        25;
            }
        }

        String expectedSimpleSignature =
                simpleSignature(
                        gt.signature()
                );

        String generatedSimpleSignature =
                simpleSignature(
                        generated.signature()
                );

        if (!expectedSimpleSignature.isBlank()
                && expectedSimpleSignature.equals(
                generatedSimpleSignature
        )) {

            score +=
                    20;
        }

        String type =
                owner.type();

        if (score >= 150
                && expectedSimpleSignature.equals(
                generatedSimpleSignature
        )) {

            type =
                    "EXACT_FQN_SIGNATURE";

        } else if (owner.type()
                .equals(
                        "SIMPLE_NAME_EQUIVALENT"
                )) {

            type =
                    "SAME_ENTITY_REPACKAGED_OR_STUBBED";

        } else if (owner.type()
                .equals(
                        "SELF_WRAPPER_EQUIVALENT"
                )) {

            type =
                    "SELF_CLASS_RECONSTRUCTION";

        } else if (owner.type()
                .equals(
                        "STRUCTURAL_STUB_ALIAS"
                )) {

            type =
                    "STRUCTURAL_STUB_ALIAS";

        } else if (kind.equals("METHOD")
                || kind.equals("CONSTRUCTOR")) {

            type =
                    "COMPATIBLE_MEMBER_ARITY_MATCH";
        }

        return new Candidate(
                expectedIndex,
                generatedIndex,
                score,
                type
        );
    }

    private static boolean obviousEquivalent(
            Observation obs,
            GroundDep gt,
            GenDep generated,
            Map<String, String> structuralAliases) {

        if (!upper(
                gt.kind()
        ).equals(
                upper(
                        generated.kind()
                )
        )) {

            return false;
        }

        if (!ownerMatch(
                obs,
                gt.owner(),
                generated.owner(),
                structuralAliases
        ).compatible()) {

            return false;
        }

        String kind =
                upper(
                        gt.kind()
                );

        if (kind.equals("METHOD")
                || kind.equals("FIELD")) {

            if (!normalizeMember(
                    gt.name()
            ).equals(
                    normalizeMember(
                            generated.name()
                    )
            )) {

                return false;
            }
        }

        if (kind.equals(
                "CONSTRUCTOR"
        )
                && !constructorMemberCompatible(
                gt.name(),
                generated.name()
        )) {

            return false;
        }

        if (kind.equals("METHOD")
                || kind.equals("CONSTRUCTOR")) {

            return arityCompatible(
                    gt.signature(),
                    generated.signature()
            );
        }

        return true;
    }

    private static OwnerMatch ownerMatch(
            Observation obs,
            String expected,
            String generated) {

        String e =
                canonicalOwner(
                        expected
                );

        String g =
                canonicalOwner(
                        generated
                );

        if (e.isBlank()
                || g.isBlank()) {

            return new OwnerMatch(
                    false,
                    0,
                    "NO_OWNER_MATCH"
            );
        }

        if (e.equalsIgnoreCase(
                g
        )) {

            return new OwnerMatch(
                    true,
                    100,
                    "EXACT_OWNER"
            );
        }

        String es =
                simpleName(
                        e
                );

        String gs =
                simpleName(
                        g
                );

        if (!es.isBlank()
                && es.equalsIgnoreCase(
                gs
        )) {

            return new OwnerMatch(
                    true,
                    75,
                    "SIMPLE_NAME_EQUIVALENT"
            );
        }

        String targetClass =
                targetClass(
                        obs.function()
                );

        String wrapperClass =
                fileBaseWithoutJava(
                        obs.file()
                );

        if (!targetClass.isBlank()
                && es.equalsIgnoreCase(
                targetClass
        )
                && gs.equalsIgnoreCase(
                wrapperClass
        )) {

            return new OwnerMatch(
                    true,
                    70,
                    "SELF_WRAPPER_EQUIVALENT"
            );
        }

        return new OwnerMatch(
                false,
                0,
                "NO_OWNER_MATCH"
        );
    }

    private static OwnerMatch ownerMatch(
            Observation obs,
            String expected,
            String generated,
            Map<String, String> structuralAliases) {

        OwnerMatch direct = ownerMatch(obs, expected, generated);
        if (direct.compatible()) {
            return direct;
        }

        String e = canonicalOwner(expected);
        String g = canonicalOwner(generated);

        if (e.isBlank() || g.isBlank() || structuralAliases == null) {
            return direct;
        }

        String mapped = structuralAliases.get(e);
        if (mapped != null && mapped.equalsIgnoreCase(g)) {
            return new OwnerMatch(
                    true,
                    65,
                    "STRUCTURAL_STUB_ALIAS"
            );
        }

        return direct;
    }

    private static Map<String, String> inferStructuralOwnerAliases(
            Observation obs,
            List<GroundDep> expected,
            List<GenDep> actual,
            List<WarningRow> warnings) {

        Map<String, Set<String>> gtProfiles = new LinkedHashMap<>();
        Map<String, Set<String>> genProfiles = new LinkedHashMap<>();

        for (GroundDep d : expected) {
            String owner = canonicalOwner(d.owner());
            String feature = ownerFeature(d.kind(), d.name(), d.signature());
            if (!owner.isBlank() && !feature.isBlank() && !platformOwner(owner)) {
                gtProfiles.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(feature);
            }
        }

        for (GenDep d : actual) {
            String owner = canonicalOwner(d.owner());
            String feature = ownerFeature(d.kind(), d.name(), d.signature());
            if (!owner.isBlank() && !feature.isBlank() && !platformOwner(owner)) {
                genProfiles.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(feature);
            }
        }

        Map<String, AliasEvidence> bestByGt = new LinkedHashMap<>();
        Map<String, AliasEvidence> bestByGen = new LinkedHashMap<>();
        Set<String> gtTies = new HashSet<>();
        Set<String> genTies = new HashSet<>();

        for (Map.Entry<String, Set<String>> ge : gtProfiles.entrySet()) {
            String gtOwner = ge.getKey();
            Set<String> gp = ge.getValue();

            if (gp.size() < 3) {
                continue;
            }

            for (Map.Entry<String, Set<String>> ae : genProfiles.entrySet()) {
                String genOwner = ae.getKey();
                Set<String> ap = ae.getValue();

                if (ap.size() < 3 || ownerMatch(obs, gtOwner, genOwner).compatible()) {
                    continue;
                }

                int shared = 0;
                for (String f : gp) {
                    if (ap.contains(f)) {
                        shared++;
                    }
                }

                int minSize = Math.min(gp.size(), ap.size());
                double overlap = minSize == 0 ? 0.0 : (double) shared / minSize;

                if (shared < 3 || (shared < 6 && overlap < 0.60)) {
                    continue;
                }

                int score = shared * 1000 + (int) Math.round(overlap * 100.0);
                AliasEvidence ev = new AliasEvidence(gtOwner, genOwner, shared, overlap, score);

                AliasEvidence oldGt = bestByGt.get(gtOwner);
                if (oldGt == null || ev.score() > oldGt.score()) {
                    bestByGt.put(gtOwner, ev);
                    gtTies.remove(gtOwner);
                } else if (ev.score() == oldGt.score()
                        && !ev.genOwner().equalsIgnoreCase(oldGt.genOwner())) {
                    gtTies.add(gtOwner);
                }

                AliasEvidence oldGen = bestByGen.get(genOwner);
                if (oldGen == null || ev.score() > oldGen.score()) {
                    bestByGen.put(genOwner, ev);
                    genTies.remove(genOwner);
                } else if (ev.score() == oldGen.score()
                        && !ev.gtOwner().equalsIgnoreCase(oldGen.gtOwner())) {
                    genTies.add(genOwner);
                }
            }
        }

        Map<String, String> aliases = new LinkedHashMap<>();

        for (Map.Entry<String, AliasEvidence> e : bestByGt.entrySet()) {
            AliasEvidence ev = e.getValue();

            if (gtTies.contains(ev.gtOwner()) || genTies.contains(ev.genOwner())) {
                continue;
            }

            AliasEvidence reciprocal = bestByGen.get(ev.genOwner());
            if (reciprocal == null
                    || !reciprocal.gtOwner().equalsIgnoreCase(ev.gtOwner())) {
                continue;
            }

            aliases.put(ev.gtOwner(), ev.genOwner());

            warnings.add(new WarningRow(
                    "INFO",
                    obs.llm(),
                    obs.repo(),
                    obs.method(),
                    obs.file(),
                    "STRUCTURAL_STUB_ALIAS_INFERRED",
                    "Repository owner=" + ev.gtOwner()
                            + " | generated owner=" + ev.genOwner()
                            + " | shared_members=" + ev.shared()
                            + " | overlap=" + String.format(Locale.ROOT, "%.3f", ev.overlap())
            ));
        }

        return aliases;
    }

    private static String ownerFeature(
            String kind,
            String name,
            String signature) {

        String k = upper(kind);
        String n = normalizeMember(name);

        if (k.equals("FIELD") && !n.isBlank()) {
            return "F|" + n;
        }

        if (k.equals("METHOD") && !n.isBlank()) {
            int a = arity(signature);
            return "M|" + n + "/" + (a < 0 ? "?" : Integer.toString(a));
        }

        return "";
    }

    private static boolean platformOwner(String owner) {
        String x = canonicalOwner(owner).toLowerCase(Locale.ROOT);
        return x.startsWith("java.")
                || x.startsWith("javax.")
                || x.startsWith("jdk.")
                || x.startsWith("sun.");
    }

    private record AliasEvidence(
            String gtOwner,
            String genOwner,
            int shared,
            double overlap,
            int score) {
    }

    private static void validateObservationUniverse(
            List<Observation> observations) {

        Set<String> keys =
                new HashSet<>();

        for (Observation o :
                observations) {

            if (!keys.add(
                    o.observationKey()
            )) {

                throw new IllegalStateException(
                        "Duplicate observation: "
                                + o.observationKey()
                );
            }
        }

        for (String llm :
                LLMS) {

            long n =
                    observations.stream()
                            .filter(
                                    o ->
                                            o.llm()
                                                    .equals(
                                                            llm
                                                    )
                            )
                            .count();

            if (n != 100) {

                throw new IllegalStateException(
                        llm
                                + " has "
                                + n
                                + " observations; expected 100."
                );
            }
        }
    }

    private static void validateFinalScores(
            List<MethodScore> scores,
            List<Decision> decisions,
            List<Extra> extras,
            List<WarningRow> warnings) {

        if (scores.size() != 500) {

            throw new IllegalStateException(
                    "Expected 500 final scores."
            );
        }

        int methodTp =
                scores.stream()
                        .mapToInt(
                                MethodScore::tp
                        )
                        .sum();

        int methodFn =
                scores.stream()
                        .mapToInt(
                                MethodScore::fn
                        )
                        .sum();

        int methodFp =
                scores.stream()
                        .mapToInt(
                                MethodScore::fp
                        )
                        .sum();

        int rowTp =
                (int)
                        decisions.stream()
                                .filter(
                                        Decision::tp
                                )
                                .count();

        int rowFn =
                (int)
                        decisions.stream()
                                .filter(
                                        d -> !d.tp()
                                )
                                .count();

        int rowFp =
                extras.size();

        if (methodTp != rowTp
                || methodFn != rowFn
                || methodFp != rowFp) {

            throw new IllegalStateException(
                    "Internal TP/FP/FN mismatch between method totals and dependency rows."
            );
        }

        long errors =
                warnings.stream()
                        .filter(
                                w ->
                                        w.severity()
                                                .equals(
                                                        "ERROR"
                                                )
                        )
                        .count();

        if (errors > 0) {

            System.out.println(
                    "WARNING: "
                            + errors
                            + " validation ERROR row(s) detected. Outputs will be written for inspection."
            );
        }
    }

    private static void writeMethodTxt(
            Path byRepo,
            MethodScore score,
            List<GroundDep> expected,
            List<GenDep> actual,
            MatchOutput matched)
            throws Exception {

        Observation o =
                score.observation();

        Path dir =
                byRepo
                        .resolve(
                                "repo"
                                        + o.repo()
                        )
                        .resolve(
                                "method_"
                                        + o.method()
                        );

        Files.createDirectories(
                dir
        );

        Path file =
                dir.resolve(
                        o.llm()
                                + ".txt"
                );

        StringBuilder b =
                new StringBuilder();

        b.append(
                "FINAL REPOSITORY DEPENDENCY MATCHING\n"
        );

        b.append(
                "====================================\n\n"
        );

        b.append(
                "Language model: "
        ).append(
                o.llm()
        ).append('\n');

        b.append(
                "Repository: repo"
        ).append(
                o.repo()
        ).append('\n');

        b.append(
                "Authoritative method: "
        ).append(
                o.method()
        ).append('\n');

        b.append(
                "Global corpus method: "
        ).append(
                o.globalMethod()
        ).append('\n');

        b.append(
                "Function: "
        ).append(
                o.function()
        ).append('\n');

        b.append(
                "Generated Java file: "
        ).append(
                o.file()
        ).append('\n');

        b.append(
                "Mapping status: "
        ).append(
                o.mappingStatus()
        ).append('\n');

        b.append(
                "Mapping selection: "
        ).append(
                o.selectionReason()
        ).append('\n');

        b.append(
                "Target preservation: "
        ).append(
                o.targetPreservation()
        ).append('\n');

        b.append(
                "Target method itself included in dependency F1: NO\n"
        );

        b.append(
                "Missing reconstruction: "
        ).append(
                o.missing()
        ).append(
                "\n\n"
        );

        b.append(
                "FINAL SCORE\n"
        );

        b.append(
                "-----------\n"
        );

        b.append(
                "Recursive GT scored rows before semantic de-duplication: "
        ).append(
                score.rawGroundScoredCount()
        ).append('\n');

        b.append(
                "Recursive GT unique dependency identities used for F1: "
        ).append(
                score.groundCount()
        ).append('\n');

        b.append(
                "Fresh generated target-rooted scored rows before semantic de-duplication: "
        ).append(
                score.rawGeneratedScoredCount()
        ).append('\n');

        b.append(
                "Fresh generated unique dependency identities used for F1: "
        ).append(
                score.generatedCount()
        ).append('\n');

        b.append(
                "NOTE: All recursive paths/depths are preserved in RAW evidence. "
                        + "F1 is set-based, therefore a repeated semantic dependency "
                        + "is scored once.\n"
        );

        b.append(
                "TP / FP / FN: "
        ).append(
                score.tp()
        ).append(
                " / "
        ).append(
                score.fp()
        ).append(
                " / "
        ).append(
                score.fn()
        ).append('\n');

        b.append(
                "Precision: "
        ).append(
                fmt(
                        score.precision()
                )
        ).append('\n');

        b.append(
                "Recall: "
        ).append(
                fmt(
                        score.recall()
                )
        ).append('\n');

        b.append(
                "F1: "
        ).append(
                fmt(
                        score.f1()
                )
        ).append('\n');

        b.append(
                "Provenance correct/comparable: "
        ).append(
                score.provenanceCorrect()
        ).append(
                " / "
        ).append(
                score.provenanceComparable()
        ).append('\n');

        b.append(
                "Provenance accuracy: "
        ).append(
                fmt(
                        score.provenanceAccuracy()
                )
        ).append(
                "\n\n"
        );

        b.append(
                "GROUND TRUTH BY KIND\n"
        );

        b.append(
                "--------------------\n"
        );

        Map<String, Long> kinds =
                expected.stream()
                        .collect(
                                Collectors.groupingBy(
                                        GroundDep::kind,
                                        TreeMap::new,
                                        Collectors.counting()
                                )
                        );

        for (var e :
                kinds.entrySet()) {

            b.append(
                    e.getKey()
            ).append(
                    ": "
            ).append(
                    e.getValue()
            ).append('\n');
        }

        b.append(
                "\nDEPENDENCY-BY-DEPENDENCY MATCHING\n"
        );

        b.append(
                "---------------------------------\n\n"
        );

        int i =
                0;

        for (Decision d :
                matched.decisions()) {

            i++;

            GroundDep gt =
                    d.expected();

            GenDep g =
                    d.generated();

            b.append(
                    '#'
            ).append(
                    i
            ).append('\n');

            b.append(
                    "Kind: "
            ).append(
                    gt.kind()
            ).append('\n');

            b.append(
                    "Depth: "
            ).append(
                    gt.depth()
            ).append('\n');

            b.append(
                    "Repository owner: "
            ).append(
                    gt.owner()
            ).append('\n');

            b.append(
                    "Repository member: "
            ).append(
                    gt.name()
            ).append('\n');

            b.append(
                    "Repository signature: "
            ).append(
                    gt.signature()
            ).append('\n');

            b.append(
                    "Repository provenance: "
            ).append(
                    gt.provenance()
            ).append('\n');

            b.append(
                    "Repository source: "
            ).append(
                    gt.source()
            ).append('\n');

            b.append(
                    "Dependency path: "
            ).append(
                    gt.path()
            ).append('\n');

            b.append(
                    "Generated owner: "
            ).append(
                    g == null
                            ? "<MISSING>"
                            : g.owner()
            ).append('\n');

            b.append(
                    "Generated member: "
            ).append(
                    g == null
                            ? "<MISSING>"
                            : g.name()
            ).append('\n');

            b.append(
                    "Generated signature: "
            ).append(
                    g == null
                            ? "<MISSING>"
                            : g.signature()
            ).append('\n');

            b.append(
                    "Generated provenance: "
            ).append(
                    g == null
                            ? "<MISSING>"
                            : g.provenance()
            ).append('\n');

            b.append(
                    "Match result: "
            ).append(
                    d.matchResult()
            ).append('\n');

            b.append(
                    "Final classification: "
            ).append(
                    d.classification()
            ).append('\n');

            b.append(
                    "TP: "
            ).append(
                    d.tp()
            ).append('\n');

            b.append(
                    "Provenance correct: "
            ).append(
                    d.provenanceCorrect()
            ).append('\n');

            b.append(
                    "Evidence: "
            ).append(
                    d.evidence()
            ).append(
                    "\n\n"
            );
        }

        b.append(
                "EXTRA GENERATED DEPENDENCIES / FALSE POSITIVES\n"
        );

        b.append(
                "----------------------------------------------\n\n"
        );

        if (matched.extras().isEmpty()) {

            b.append(
                    "<NONE>\n"
            );

        } else {

            int x =
                    0;

            for (Extra e :
                    matched.extras()) {

                x++;

                GenDep g =
                        e.generated();

                b.append(
                        '#'
                ).append(
                        x
                ).append('\n');

                b.append(
                        "Kind: "
                ).append(
                        g.kind()
                ).append('\n');

                b.append(
                        "Depth: "
                ).append(
                        g.depth()
                ).append('\n');

                b.append(
                        "Owner: "
                ).append(
                        g.owner()
                ).append('\n');

                b.append(
                        "Member: "
                ).append(
                        g.name()
                ).append('\n');

                b.append(
                        "Signature: "
                ).append(
                        g.signature()
                ).append('\n');

                b.append(
                        "Provenance: "
                ).append(
                        g.provenance()
                ).append('\n');

                b.append(
                        "Generated path: "
                ).append(
                        g.path()
                ).append('\n');

                b.append(
                        "Generated source: "
                ).append(
                        g.source()
                ).append('\n');

                b.append(
                        "Classification: FP\n"
                );

                b.append(
                        "Evidence: "
                ).append(
                        e.evidence()
                ).append(
                        "\n\n"
                );
            }
        }

        b.append(
                "COMPLETE SCORED GENERATED TARGET-ROOTED INVENTORY\n"
        );

        b.append(
                "------------------------------------------------\n"
        );

        if (actual.isEmpty()) {

            b.append(
                    "<NONE>\n"
            );

        } else {

            for (GenDep g :
                    actual) {

                b.append(
                        "depth="
                ).append(
                        g.depth()
                ).append(
                        " | "
                ).append(
                        g.kind()
                ).append(
                        " | "
                ).append(
                        g.owner()
                ).append(
                        " | "
                ).append(
                        g.name()
                ).append(
                        " | "
                ).append(
                        g.signature()
                ).append(
                        " | "
                ).append(
                        g.provenance()
                ).append('\n');

                b.append(
                        "  path: "
                ).append(
                        g.path()
                ).append('\n');
            }
        }

        Files.writeString(
                file,
                b.toString(),
                StandardCharsets.UTF_8
        );
    }

    private static void writeMethodCsv(
            Path byRepo,
            MethodScore score,
            MatchOutput matched)
            throws Exception {

        Observation o =
                score.observation();

        Path dir =
                byRepo
                        .resolve(
                                "repo"
                                        + o.repo()
                        )
                        .resolve(
                                "method_"
                                        + o.method()
                        );

        Files.createDirectories(
                dir
        );

        Path file =
                dir.resolve(
                        o.llm()
                                + ".csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,global_method,function,file,"
                            + "target_preservation,target_self_scored,"
                            + "row_type,kind,depth,"
                            + "expected_owner,expected_name,expected_signature,"
                            + "expected_provenance,expected_source,dependency_path,"
                            + "generated_owner,generated_name,generated_signature,"
                            + "generated_provenance,generated_source,generated_path,"
                            + "match_result,classification,tp,provenance_correct,evidence"
            );

            w.newLine();

            for (Decision d :
                    matched.decisions()) {

                GroundDep gt =
                        d.expected();

                GenDep g =
                        d.generated();

                w.write(
                        csv(
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.globalMethod(),
                                o.function(),
                                o.file(),

                                o.targetPreservation(),
                                false,

                                "EXPECTED",
                                gt.kind(),
                                gt.depth(),

                                gt.owner(),
                                gt.name(),
                                gt.signature(),
                                gt.provenance(),
                                gt.source(),
                                gt.path(),

                                g == null
                                        ? ""
                                        : g.owner(),

                                g == null
                                        ? ""
                                        : g.name(),

                                g == null
                                        ? ""
                                        : g.signature(),

                                g == null
                                        ? ""
                                        : g.provenance(),

                                g == null
                                        ? ""
                                        : g.source(),

                                g == null
                                        ? ""
                                        : g.path(),

                                d.matchResult(),
                                d.classification(),
                                d.tp(),
                                d.provenanceCorrect(),
                                d.evidence()
                        )
                );

                w.newLine();
            }

            for (Extra e :
                    matched.extras()) {

                GenDep g =
                        e.generated();

                w.write(
                        csv(
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.globalMethod(),
                                o.function(),
                                o.file(),

                                o.targetPreservation(),
                                false,

                                "GENERATED_EXTRA",
                                g.kind(),
                                g.depth(),

                                "",
                                "",
                                "",
                                "",
                                "",
                                "",

                                g.owner(),
                                g.name(),
                                g.signature(),
                                g.provenance(),
                                g.source(),
                                g.path(),

                                "UNMATCHED_GENERATED",
                                "FP",
                                false,
                                false,
                                e.evidence()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeMethodRawAndUniqueInventories(
            Path byRepo,
            MethodScore score,
            List<GroundDep> rawGround,
            List<GroundDep> uniqueGround,
            List<GenDep> rawGenerated,
            List<GenDep> uniqueGenerated)
            throws Exception {

        Observation o =
                score.observation();

        Path dir =
                byRepo
                        .resolve(
                                "repo"
                                        + o.repo()
                        )
                        .resolve(
                                "method_"
                                        + o.method()
                        );

        Files.createDirectories(
                dir
        );

        Path gtRaw =
                dir.resolve(
                        "GROUND_TRUTH_RAW_RECURSIVE.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             gtRaw,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "repo,method,global_method,function,kind,owner,name,signature,"
                            + "provenance,depth,parent,dependency_path,source,resolution"
            );

            w.newLine();

            for (GroundDep d :
                    rawGround) {

                w.write(
                        csv(
                                d.repo(),
                                d.method(),
                                d.globalMethod(),
                                d.function(),
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.provenance(),
                                d.depth(),
                                d.parent(),
                                d.path(),
                                d.source(),
                                d.resolution()
                        )
                );

                w.newLine();
            }
        }

        Path gtUnique =
                dir.resolve(
                        "GROUND_TRUTH_UNIQUE_SCORED.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             gtUnique,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "repo,method,global_method,function,kind,owner,name,signature,"
                            + "provenance,depth,parent,dependency_path,source,resolution"
            );

            w.newLine();

            for (GroundDep d :
                    uniqueGround) {

                w.write(
                        csv(
                                d.repo(),
                                d.method(),
                                d.globalMethod(),
                                d.function(),
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.provenance(),
                                d.depth(),
                                d.parent(),
                                d.path(),
                                d.source(),
                                d.resolution()
                        )
                );

                w.newLine();
            }
        }

        Path genRaw =
                dir.resolve(
                        o.llm()
                                + "_GENERATED_RAW_RECURSIVE.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             genRaw,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo_folder,file,repo,method,global_method,function,"
                            + "kind,owner,name,signature,provenance,depth,parent,"
                            + "dependency_path,source,resolution"
            );

            w.newLine();

            for (GenDep d :
                    rawGenerated) {

                w.write(
                        csv(
                                d.llm(),
                                d.repoFolder(),
                                d.file(),
                                d.repo(),
                                d.method(),
                                d.globalMethod(),
                                d.function(),
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.provenance(),
                                d.depth(),
                                d.parent(),
                                d.path(),
                                d.source(),
                                d.resolution()
                        )
                );

                w.newLine();
            }
        }

        Path genUnique =
                dir.resolve(
                        o.llm()
                                + "_GENERATED_UNIQUE_SCORED.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             genUnique,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo_folder,file,repo,method,global_method,function,"
                            + "kind,owner,name,signature,provenance,depth,parent,"
                            + "dependency_path,source,resolution"
            );

            w.newLine();

            for (GenDep d :
                    uniqueGenerated) {

                w.write(
                        csv(
                                d.llm(),
                                d.repoFolder(),
                                d.file(),
                                d.repo(),
                                d.method(),
                                d.globalMethod(),
                                d.function(),
                                d.kind(),
                                d.owner(),
                                d.name(),
                                d.signature(),
                                d.provenance(),
                                d.depth(),
                                d.parent(),
                                d.path(),
                                d.source(),
                                d.resolution()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeCleanAll500(
            Path out,
            List<MethodScore> scores)
            throws Exception {

        Path file =
                out.resolve(
                        "FINAL_F1_ALL_500.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,global_method,function,file,"
                            + "mapping_status,mapping_selection,target_preservation,"
                            + "target_method_scored_in_dependency_f1,"
                            + "missing_reconstruction,"
                            + "raw_scored_ground_rows,"
                            + "ground_truth_unique_scored_dependencies,"
                            + "raw_scored_generated_rows,"
                            + "generated_unique_scored_dependencies,"
                            + "tp,fp,fn,precision,recall,f1,"
                            + "provenance_correct,provenance_comparable,"
                            + "provenance_accuracy"
            );

            w.newLine();

            for (MethodScore s :
                    scores) {

                Observation o =
                        s.observation();

                w.write(
                        csv(
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.globalMethod(),
                                o.function(),
                                o.file(),

                                o.mappingStatus(),
                                o.selectionReason(),
                                o.targetPreservation(),

                                false,

                                o.missing(),

                                s.rawGroundScoredCount(),
                                s.groundCount(),

                                s.rawGeneratedScoredCount(),
                                s.generatedCount(),

                                s.tp(),
                                s.fp(),
                                s.fn(),

                                fmt(
                                        s.precision()
                                ),

                                fmt(
                                        s.recall()
                                ),

                                fmt(
                                        s.f1()
                                ),

                                s.provenanceCorrect(),
                                s.provenanceComparable(),

                                fmt(
                                        s.provenanceAccuracy()
                                )
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeAllDependencyRows(
            Path out,
            List<Decision> decisions,
            List<Extra> extras)
            throws Exception {

        Path file =
                out.resolve(
                        "ALL_FINAL_DEPENDENCY_ROWS.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,function,file,row_type,kind,depth,"
                            + "expected_owner,expected_name,expected_signature,"
                            + "expected_provenance,expected_source,dependency_path,"
                            + "generated_owner,generated_name,generated_signature,"
                            + "generated_provenance,generated_source,generated_path,"
                            + "match_result,classification,tp,provenance_correct,evidence"
            );

            w.newLine();

            for (Decision d :
                    decisions) {

                writeDecisionRow(
                        w,
                        d
                );
            }

            for (Extra e :
                    extras) {

                writeExtraRow(
                        w,
                        e
                );
            }
        }
    }

    private static void writeDecisionSubset(
            Path file,
            List<Decision> decisions,
            String classification)
            throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,function,file,kind,depth,"
                            + "expected_owner,expected_name,expected_signature,"
                            + "expected_provenance,expected_source,dependency_path,"
                            + "generated_owner,generated_name,generated_signature,"
                            + "generated_provenance,match_result,classification,"
                            + "provenance_correct,evidence"
            );

            w.newLine();

            for (Decision d :
                    decisions) {

                if (!d.classification()
                        .equals(
                                classification
                        )) {

                    continue;
                }

                Observation o =
                        d.observation();

                GroundDep gt =
                        d.expected();

                GenDep g =
                        d.generated();

                w.write(
                        csv(
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.function(),
                                o.file(),

                                gt.kind(),
                                gt.depth(),
                                gt.owner(),
                                gt.name(),
                                gt.signature(),
                                gt.provenance(),
                                gt.source(),
                                gt.path(),

                                g == null
                                        ? ""
                                        : g.owner(),

                                g == null
                                        ? ""
                                        : g.name(),

                                g == null
                                        ? ""
                                        : g.signature(),

                                g == null
                                        ? ""
                                        : g.provenance(),

                                d.matchResult(),
                                d.classification(),
                                d.provenanceCorrect(),
                                d.evidence()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeExtras(
            Path file,
            List<Extra> extras)
            throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,function,file,kind,depth,"
                            + "generated_owner,generated_name,generated_signature,"
                            + "generated_provenance,generated_source,generated_path,"
                            + "classification,evidence"
            );

            w.newLine();

            for (Extra e :
                    extras) {

                Observation o =
                        e.observation();

                GenDep g =
                        e.generated();

                w.write(
                        csv(
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.function(),
                                o.file(),

                                g.kind(),
                                g.depth(),
                                g.owner(),
                                g.name(),
                                g.signature(),
                                g.provenance(),
                                g.source(),
                                g.path(),

                                e.classification(),
                                e.evidence()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeDecisionRow(
            BufferedWriter w,
            Decision d)
            throws Exception {

        Observation o =
                d.observation();

        GroundDep gt =
                d.expected();

        GenDep g =
                d.generated();

        w.write(
                csv(
                        o.llm(),
                        o.repo(),
                        o.method(),
                        o.function(),
                        o.file(),

                        "EXPECTED",

                        gt.kind(),
                        gt.depth(),
                        gt.owner(),
                        gt.name(),
                        gt.signature(),
                        gt.provenance(),
                        gt.source(),
                        gt.path(),

                        g == null
                                ? ""
                                : g.owner(),

                        g == null
                                ? ""
                                : g.name(),

                        g == null
                                ? ""
                                : g.signature(),

                        g == null
                                ? ""
                                : g.provenance(),

                        g == null
                                ? ""
                                : g.source(),

                        g == null
                                ? ""
                                : g.path(),

                        d.matchResult(),
                        d.classification(),
                        d.tp(),
                        d.provenanceCorrect(),
                        d.evidence()
                )
        );

        w.newLine();
    }

    private static void writeExtraRow(
            BufferedWriter w,
            Extra e)
            throws Exception {

        Observation o =
                e.observation();

        GenDep g =
                e.generated();

        w.write(
                csv(
                        o.llm(),
                        o.repo(),
                        o.method(),
                        o.function(),
                        o.file(),

                        "GENERATED_EXTRA",

                        g.kind(),
                        g.depth(),

                        "",
                        "",
                        "",
                        "",
                        "",
                        "",

                        g.owner(),
                        g.name(),
                        g.signature(),
                        g.provenance(),
                        g.source(),
                        g.path(),

                        "UNMATCHED_GENERATED",
                        "FP",
                        false,
                        false,
                        e.evidence()
                )
        );

        w.newLine();
    }

    private static void writeByLlm(
            Path out,
            Path byLlm,
            List<MethodScore> scores,
            List<Decision> decisions,
            List<Extra> extras)
            throws Exception {

        Path summary =
                out.resolve(
                        "FINAL_F1_BY_LLM.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             summary,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,methods,micro_tp,micro_fp,micro_fn,"
                            + "micro_precision,micro_recall,micro_f1,"
                            + "macro_precision,macro_recall,macro_f1,"
                            + "provenance_accuracy,missing_reconstructions"
            );

            w.newLine();

            for (String llm :
                    LLMS) {

                List<MethodScore> rows =
                        scores.stream()
                                .filter(
                                        s ->
                                                s.observation()
                                                        .llm()
                                                        .equals(
                                                                llm
                                                        )
                                )
                                .toList();

                int tp =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::tp
                                )
                                .sum();

                int fp =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::fp
                                )
                                .sum();

                int fn =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::fn
                                )
                                .sum();

                double p =
                        precision(
                                tp,
                                fp
                        );

                double r =
                        recall(
                                tp,
                                fn
                        );

                double f =
                        f1(
                                p,
                                r
                        );

                double macroP =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::precision
                                )
                                .average()
                                .orElse(0);

                double macroR =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::recall
                                )
                                .average()
                                .orElse(0);

                double macroF =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::f1
                                )
                                .average()
                                .orElse(0);

                int pc =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::provenanceCorrect
                                )
                                .sum();

                int pn =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::provenanceComparable
                                )
                                .sum();

                double pa =
                        pn == 0
                                ? 0
                                : (double)
                                pc
                                / pn;

                long missing =
                        rows.stream()
                                .filter(
                                        s ->
                                                s.observation()
                                                        .missing()
                                )
                                .count();

                w.write(
                        csv(
                                llm,
                                rows.size(),

                                tp,
                                fp,
                                fn,

                                fmt(p),
                                fmt(r),
                                fmt(f),

                                fmt(
                                        macroP
                                ),

                                fmt(
                                        macroR
                                ),

                                fmt(
                                        macroF
                                ),

                                fmt(
                                        pa
                                ),

                                missing
                        )
                );

                w.newLine();

                Path dir =
                        byLlm.resolve(
                                llm
                        );

                Files.createDirectories(
                        dir
                );

                writeLlmMethodCsv(
                        dir.resolve(
                                "ALL_METHODS.csv"
                        ),
                        rows
                );

                writeLlmDependencyCsv(
                        dir.resolve(
                                "ALL_DEPENDENCY_ROWS.csv"
                        ),
                        llm,
                        decisions,
                        extras
                );

                String txt =
                        "FINAL LLM SUMMARY\n"
                                + "=================\n\n"
                                + "LLM: "
                                + llm
                                + "\nMethods: "
                                + rows.size()
                                + "\nTP / FP / FN: "
                                + tp
                                + " / "
                                + fp
                                + " / "
                                + fn
                                + "\nMicro precision: "
                                + fmt(p)
                                + "\nMicro recall: "
                                + fmt(r)
                                + "\nMicro F1: "
                                + fmt(f)
                                + "\nMacro precision: "
                                + fmt(
                                macroP
                        )
                                + "\nMacro recall: "
                                + fmt(
                                macroR
                        )
                                + "\nMacro F1: "
                                + fmt(
                                macroF
                        )
                                + "\nProvenance accuracy: "
                                + fmt(
                                pa
                        )
                                + "\nMissing reconstructions: "
                                + missing
                                + "\n";

                Files.writeString(
                        dir.resolve(
                                "LLM_SUMMARY.txt"
                        ),
                        txt,
                        StandardCharsets.UTF_8
                );
            }
        }
    }

    private static void writeLlmMethodCsv(
            Path file,
            List<MethodScore> rows)
            throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,function,file,target_preservation,"
                            + "tp,fp,fn,precision,recall,f1,missing_reconstruction"
            );

            w.newLine();

            for (MethodScore s :
                    rows) {

                Observation o =
                        s.observation();

                w.write(
                        csv(
                                o.llm(),
                                o.repo(),
                                o.method(),
                                o.function(),
                                o.file(),
                                o.targetPreservation(),

                                s.tp(),
                                s.fp(),
                                s.fn(),

                                fmt(
                                        s.precision()
                                ),

                                fmt(
                                        s.recall()
                                ),

                                fmt(
                                        s.f1()
                                ),

                                o.missing()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeLlmDependencyCsv(
            Path file,
            String llm,
            List<Decision> decisions,
            List<Extra> extras)
            throws Exception {

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,function,file,row_type,kind,depth,"
                            + "expected_owner,expected_name,expected_signature,"
                            + "generated_owner,generated_name,generated_signature,"
                            + "classification,match_result"
            );

            w.newLine();

            for (Decision d :
                    decisions) {

                if (!d.observation()
                        .llm()
                        .equals(
                                llm
                        )) {

                    continue;
                }

                Observation o =
                        d.observation();

                GroundDep gt =
                        d.expected();

                GenDep g =
                        d.generated();

                w.write(
                        csv(
                                llm,
                                o.repo(),
                                o.method(),
                                o.function(),
                                o.file(),

                                "EXPECTED",

                                gt.kind(),
                                gt.depth(),
                                gt.owner(),
                                gt.name(),
                                gt.signature(),

                                g == null
                                        ? ""
                                        : g.owner(),

                                g == null
                                        ? ""
                                        : g.name(),

                                g == null
                                        ? ""
                                        : g.signature(),

                                d.classification(),
                                d.matchResult()
                        )
                );

                w.newLine();
            }

            for (Extra e :
                    extras) {

                if (!e.observation()
                        .llm()
                        .equals(
                                llm
                        )) {

                    continue;
                }

                Observation o =
                        e.observation();

                GenDep g =
                        e.generated();

                w.write(
                        csv(
                                llm,
                                o.repo(),
                                o.method(),
                                o.function(),
                                o.file(),

                                "GENERATED_EXTRA",

                                g.kind(),
                                g.depth(),

                                "",
                                "",
                                "",

                                g.owner(),
                                g.name(),
                                g.signature(),

                                "FP",
                                "UNMATCHED_GENERATED"
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeByRepository(
            Path out,
            Path byRepo,
            List<MethodScore> scores)
            throws Exception {

        Map<Integer, List<MethodScore>> grouped =
                scores.stream()
                        .collect(
                                Collectors.groupingBy(
                                        s ->
                                                s.observation()
                                                        .repo(),
                                        TreeMap::new,
                                        Collectors.toList()
                                )
                        );

        Path file =
                out.resolve(
                        "FINAL_F1_BY_REPOSITORY.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "repo,observations,tp,fp,fn,precision,recall,f1,"
                            + "macro_precision,macro_recall,macro_f1"
            );

            w.newLine();

            for (var e :
                    grouped.entrySet()) {

                List<MethodScore> rows =
                        e.getValue();

                int tp =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::tp
                                )
                                .sum();

                int fp =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::fp
                                )
                                .sum();

                int fn =
                        rows.stream()
                                .mapToInt(
                                        MethodScore::fn
                                )
                                .sum();

                double p =
                        precision(
                                tp,
                                fp
                        );

                double r =
                        recall(
                                tp,
                                fn
                        );

                double f =
                        f1(
                                p,
                                r
                        );

                double mp =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::precision
                                )
                                .average()
                                .orElse(0);

                double mr =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::recall
                                )
                                .average()
                                .orElse(0);

                double mf =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::f1
                                )
                                .average()
                                .orElse(0);

                w.write(
                        csv(
                                e.getKey(),
                                rows.size(),
                                tp,
                                fp,
                                fn,
                                fmt(p),
                                fmt(r),
                                fmt(f),
                                fmt(mp),
                                fmt(mr),
                                fmt(mf)
                        )
                );

                w.newLine();

                Path dir =
                        byRepo.resolve(
                                "repo"
                                        + e.getKey()
                        );

                Files.createDirectories(
                        dir
                );

                try (BufferedWriter rw =
                             Files.newBufferedWriter(
                                     dir.resolve(
                                             "REPO_SUMMARY.csv"
                                     ),
                                     StandardCharsets.UTF_8
                             )) {

                    rw.write(
                            "llm,method,function,file,target_preservation,"
                                    + "tp,fp,fn,precision,recall,f1,missing_reconstruction"
                    );

                    rw.newLine();

                    for (MethodScore s :
                            rows.stream()
                                    .sorted(
                                            Comparator
                                                    .comparingInt(
                                                            (MethodScore x) ->
                                                                    x.observation()
                                                                            .method()
                                                    )
                                                    .thenComparing(
                                                            x ->
                                                                    x.observation()
                                                                            .llm()
                                                    )
                                    )
                                    .toList()) {

                        Observation o =
                                s.observation();

                        rw.write(
                                csv(
                                        o.llm(),
                                        o.method(),
                                        o.function(),
                                        o.file(),
                                        o.targetPreservation(),

                                        s.tp(),
                                        s.fp(),
                                        s.fn(),

                                        fmt(
                                                s.precision()
                                        ),

                                        fmt(
                                                s.recall()
                                        ),

                                        fmt(
                                                s.f1()
                                        ),

                                        o.missing()
                                )
                        );

                        rw.newLine();
                    }
                }

                String txt =
                        "FINAL REPOSITORY SUMMARY\n"
                                + "========================\n\n"
                                + "Repository: repo"
                                + e.getKey()
                                + "\nObservations: "
                                + rows.size()
                                + "\nTP / FP / FN: "
                                + tp
                                + " / "
                                + fp
                                + " / "
                                + fn
                                + "\nPrecision: "
                                + fmt(p)
                                + "\nRecall: "
                                + fmt(r)
                                + "\nF1: "
                                + fmt(f)
                                + "\nMacro F1: "
                                + fmt(mf)
                                + "\n";

                Files.writeString(
                        dir.resolve(
                                "REPO_SUMMARY.txt"
                        ),
                        txt,
                        StandardCharsets.UTF_8
                );
            }
        }
    }

    private static void writeByMethod(
            Path out,
            List<MethodScore> scores)
            throws Exception {

        Map<String, List<MethodScore>> grouped =
                scores.stream()
                        .collect(
                                Collectors.groupingBy(
                                        s ->
                                                s.observation()
                                                        .methodKey(),
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                )
                        );

        Path file =
                out.resolve(
                        "FINAL_F1_BY_METHOD.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "repo,method,function,models,mean_precision,mean_recall,"
                            + "mean_f1,min_f1,max_f1"
            );

            w.newLine();

            List<List<MethodScore>> groups =
                    new ArrayList<>(
                            grouped.values()
                    );

            groups.sort(
                    Comparator
                            .comparingInt(
                                    (List<MethodScore> g) ->
                                            g.get(0)
                                                    .observation()
                                                    .repo()
                            )
                            .thenComparingInt(
                                    g ->
                                            g.get(0)
                                                    .observation()
                                                    .method()
                            )
            );

            for (List<MethodScore> rows :
                    groups) {

                Observation first =
                        rows.get(0)
                                .observation();

                if (rows.size()
                        != 5) {

                    throw new IllegalStateException(
                            "Method missing one or more LLM observations."
                    );
                }

                double p =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::precision
                                )
                                .average()
                                .orElse(0);

                double r =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::recall
                                )
                                .average()
                                .orElse(0);

                double f =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::f1
                                )
                                .average()
                                .orElse(0);

                double min =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::f1
                                )
                                .min()
                                .orElse(0);

                double max =
                        rows.stream()
                                .mapToDouble(
                                        MethodScore::f1
                                )
                                .max()
                                .orElse(0);

                w.write(
                        csv(
                                first.repo(),
                                first.method(),
                                first.function(),
                                rows.size(),
                                fmt(p),
                                fmt(r),
                                fmt(f),
                                fmt(min),
                                fmt(max)
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeByKind(
            Path out,
            List<Decision> decisions,
            List<Extra> extras)
            throws Exception {

        Map<String, Counts> counts =
                new TreeMap<>();

        for (Decision d :
                decisions) {

            Counts c =
                    counts.computeIfAbsent(
                            d.expected()
                                    .kind(),
                            ignored ->
                                    new Counts()
                    );

            if (d.tp()) {

                c.tp++;

            } else {

                c.fn++;
            }
        }

        for (Extra e :
                extras) {

            Counts c =
                    counts.computeIfAbsent(
                            e.generated()
                                    .kind(),
                            ignored ->
                                    new Counts()
                    );

            c.fp++;
        }

        Path file =
                out.resolve(
                        "FINAL_F1_BY_KIND.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "kind,tp,fp,fn,precision,recall,f1"
            );

            w.newLine();

            for (var e :
                    counts.entrySet()) {

                Counts c =
                        e.getValue();

                double p =
                        precision(
                                c.tp,
                                c.fp
                        );

                double r =
                        recall(
                                c.tp,
                                c.fn
                        );

                w.write(
                        csv(
                                e.getKey(),
                                c.tp,
                                c.fp,
                                c.fn,
                                fmt(p),
                                fmt(r),
                                fmt(
                                        f1(
                                                p,
                                                r
                                        )
                                )
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeByDepth(
            Path out,
            List<Decision> decisions)
            throws Exception {

        Map<String, Counts> grouped =
                new TreeMap<>();

        for (Decision d :
                decisions) {

            String key =
                    d.observation()
                            .llm()
                            + "|"
                            + d.expected()
                            .depth();

            Counts c =
                    grouped.computeIfAbsent(
                            key,
                            ignored ->
                                    new Counts()
                    );

            if (d.tp()) {

                c.tp++;

            } else {

                c.fn++;
            }
        }

        Path file =
                out.resolve(
                        "FINAL_RECALL_BY_DEPTH.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,depth,tp,fn,expected,recall"
            );

            w.newLine();

            for (var e :
                    grouped.entrySet()) {

                String[] parts =
                        e.getKey()
                                .split(
                                        "\\|"
                                );

                Counts c =
                        e.getValue();

                w.write(
                        csv(
                                parts[0],
                                parts[1],
                                c.tp,
                                c.fn,
                                c.tp + c.fn,
                                fmt(
                                        recall(
                                                c.tp,
                                                c.fn
                                        )
                                )
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeMappingAudit(
            Path out,
            List<MappingAuditRow> rows)
            throws Exception {

        Path file =
                out.resolve(
                        "MAPPING_AUDIT.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "llm,repo,method,file,status,evidence"
            );

            w.newLine();

            for (MappingAuditRow r :
                    rows) {

                w.write(
                        csv(
                                r.llm(),
                                r.repo(),
                                r.method(),
                                r.file(),
                                r.status(),
                                r.evidence()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeWarnings(
            Path out,
            List<WarningRow> rows)
            throws Exception {

        Path file =
                out.resolve(
                        "VALIDATION_WARNINGS.csv"
                );

        try (BufferedWriter w =
                     Files.newBufferedWriter(
                             file,
                             StandardCharsets.UTF_8
                     )) {

            w.write(
                    "severity,llm,repo,method,file,code,details"
            );

            w.newLine();

            for (WarningRow r :
                    rows) {

                w.write(
                        csv(
                                r.severity(),
                                r.llm(),
                                r.repo(),
                                r.method(),
                                r.file(),
                                r.code(),
                                r.details()
                        )
                );

                w.newLine();
            }
        }
    }

    private static void writeSummary(
            Path out,
            List<MethodScore> scores,
            List<Decision> decisions,
            List<Extra> extras,
            List<MappingAuditRow> mappingAudit,
            List<WarningRow> warnings,
            int rawGtCount,
            int rawGenCount,
            int targetSelfExcludedCount)
            throws Exception {

        int tp =
                scores.stream()
                        .mapToInt(
                                MethodScore::tp
                        )
                        .sum();

        int fp =
                scores.stream()
                        .mapToInt(
                                MethodScore::fp
                        )
                        .sum();

        int fn =
                scores.stream()
                        .mapToInt(
                                MethodScore::fn
                        )
                        .sum();

        double p =
                precision(
                        tp,
                        fp
                );

        double r =
                recall(
                        tp,
                        fn
                );

        double f =
                f1(
                        p,
                        r
                );

        long missing =
                scores.stream()
                        .filter(
                                s ->
                                        s.observation()
                                                .missing()
                        )
                        .count();

        long errors =
                warnings.stream()
                        .filter(
                                w ->
                                        w.severity()
                                                .equals(
                                                        "ERROR"
                                                )
                        )
                        .count();

        long warns =
                warnings.stream()
                        .filter(
                                w ->
                                        w.severity()
                                                .equals(
                                                        "WARN"
                                                )
                        )
                        .count();

        String text =
                """
                FINAL DEPENDENCY EVALUATION
                =================================

                This is a fresh RQ2 scoring run built from repository-derived recursive
                ground truth plus a fresh target-rooted analysis of the selected generated
                Java files.

                Old TP / FP / FN / F1 values and old dependency-match classifications
                were NOT reused.

                DATASET
                -------
                Authoritative methods: 100
                LLMs: 5
                Final method-LLM observations: 500
                Missing reconstructions: %d

                RAW INPUTS
                ----------
                Raw repository-ground-truth rows: %d
                Fresh generated dependency rows: %d

                TARGET METHOD POLICY
                --------------------
                The authoritative target method itself is preserved and reported separately
                through target-preservation metadata.

                The target METHOD identity itself is NOT included in dependency F1 because
                the target method was supplied to the LLM rather than reconstructed.

                Excluded target-self dependency rows: %d

                The following remain scored:
                - target return type
                - target parameter types
                - target body dependencies
                - referenced types
                - invoked methods
                - constructors
                - fields
                - interfaces
                - superclasses
                - annotations
                - recursively reachable dependencies

                SCORING UNIVERSE
                ----------------
                Included:
                - repository dependencies
                - external dependencies
                - JDK reference/API dependencies
                - TYPE
                - METHOD
                - CONSTRUCTOR
                - FIELD
                - SUPERCLASS
                - INTERFACE
                - ANNOTATION

                Excluded:
                - primitive TYPE dependencies
                - void
                - execution-harness-only generated context
                - authoritative target METHOD identity itself

                GROUND TRUTH
                ------------
                The repository benchmark is the recursive dependency closure rooted at the
                authoritative target method.

                Repository-owned helpers/types/members may occur in other repository source
                files and are recursively followed by the repository-ground-truth extraction
                pipeline.

                All recursive path/depth evidence is retained.

                Primary F1 is set-based: the same semantic dependency contributes once even
                when reached through multiple recursive paths.

                Different dependency identities remain separate, e.g.:

                    TYPE   java.util.List
                    METHOD java.util.List.add/1
                    METHOD java.util.List.size/0

                GENERATED CONTEXT
                -----------------
                Generated dependencies are freshly extracted from the actual selected Java
                reconstruction.

                The whole generated file is parsed/indexed so that nested/static/local stubs,
                helper methods, fields, constructors and interfaces can be discovered.

                Scoring follows only target-rooted reconstructed context.

                Main/demo/test-harness-only context is not scored merely because it exists
                elsewhere in the standalone Java file.

                MATCHING
                --------
                Matching is deterministic and one-to-one.

                Examples eligible for TP:

                    java.util.List       <-> List
                    java.util.ArrayList  <-> ArrayList
                    java.lang.String     <-> String

                    repository FQN
                        <->
                    same simple-name local/static/nested reconstruction

                METHOD:
                    compatible owner
                    + same member name
                    + compatible arity/signature

                FIELD:
                    compatible owner
                    + same field name

                CONSTRUCTOR:
                    compatible owner/type
                    + compatible arity

                SUPERCLASS / INTERFACE / ANNOTATION:
                    compatible entity identity

                Each generated dependency may satisfy at most one repository requirement.

                Remaining expected dependencies are FN.
                Remaining target-rooted generated dependencies are FP.

                VERIFIED MAPPING OVERRIDES
                --------------------------
                Gemini:
                    method_1_sherdogparser_gemini.java
                    -> repo7 method1
                    -> FighterParser.parseDocument

                Qwen:
                    method_2_TraJ_QWEN.java
                    -> repo15 method2
                    -> TrajectorySplineFitLegacy.calculateSpline
                    -> global method 6707

                FINAL TOTALS
                ------------
                TP: %d
                FP: %d
                FN: %d

                Precision: %.6f
                Recall: %.6f
                F1: %.6f

                DEPENDENCY ROWS
                ---------------
                Expected-side decision rows: %d
                Generated FP rows: %d

                VALIDATION
                ----------
                ERROR warnings: %d
                WARN warnings: %d

                Certification requires:
                - exactly 100 authoritative methods
                - exactly 500 observations
                - exactly 100 observations per LLM
                - exactly 500 method TXT reports
                - exactly 500 method CSV reports
                - no generated file assigned to multiple authoritative targets
                - no unresolved mapping ambiguity
                - no target-method self identity surviving scored sets
                - no obvious equivalent dependency left as FN + FP
                - zero ERROR rows

                OUTPUTS
                -------
                FINAL_SUMMARY.txt

                FINAL_F1_ALL_500.csv
                FINAL_F1_BY_LLM.csv
                FINAL_F1_BY_REPOSITORY.csv
                FINAL_F1_BY_METHOD.csv
                FINAL_F1_BY_KIND.csv
                FINAL_RECALL_BY_DEPTH.csv

                ALL_FINAL_DEPENDENCY_ROWS.csv
                ALL_FINAL_TP.csv
                ALL_FINAL_FN.csv
                ALL_FINAL_FP.csv

                MAPPING_AUDIT.csv
                VALIDATION_WARNINGS.csv

                TARGET_SELF_DEPENDENCIES_EXCLUDED.csv

                FRESH_GENERATED_ANALYSIS_AUDIT.csv
                FRESH_GENERATED_DEPENDENCIES_RAW.csv

                GROUND_TRUTH_RECURSIVE_EXTRACTED_ROWS.csv
                GROUND_TRUTH_UNIQUE_SCORED_IDENTITIES.csv
                GENERATED_UNIQUE_SCORED_IDENTITIES.csv

                by_repository/repoN/REPO_SUMMARY.txt
                by_repository/repoN/REPO_SUMMARY.csv

                by_repository/repoN/method_N/
                    GROUND_TRUTH_RAW_RECURSIVE.csv
                    GROUND_TRUTH_UNIQUE_SCORED.csv

                    <llm>_GENERATED_RAW_RECURSIVE.csv
                    <llm>_GENERATED_UNIQUE_SCORED.csv

                    <llm>.txt
                    <llm>.csv

                by_llm/<llm>/
                    LLM_SUMMARY.txt
                    ALL_METHODS.csv
                    ALL_DEPENDENCY_ROWS.csv
                """.formatted(
                        missing,
                        rawGtCount,
                        rawGenCount,
                        targetSelfExcludedCount,

                        tp,
                        fp,
                        fn,
                        p,
                        r,
                        f,

                        decisions.size(),
                        extras.size(),

                        errors,
                        warns
                );

        Files.writeString(
                out.resolve(
                        "FINAL_SUMMARY.txt"
                ),
                text,
                StandardCharsets.UTF_8
        );
    }

    private static List<MappingRow> readMappings(
            Path file)
            throws Exception {

        CsvTable table =
                CsvTable.read(
                        file
                );

        List<MappingRow> out =
                new ArrayList<>();

        for (Map<String, String> row :
                table.rows) {

            String llm =
                    lower(
                            v(
                                    row,
                                    "llm"
                            )
                    );

            int repo =
                    integer(
                            v(
                                    row,
                                    "repo"
                            )
                    );

            int method =
                    integer(
                            v(
                                    row,
                                    "method"
                            )
                    );

            String filename =
                    v(
                            row,
                            "file"
                    );

            if (llm.isBlank()
                    || repo <= 0
                    || method <= 0
                    || filename.isBlank()) {

                continue;
            }

            out.add(
                    new MappingRow(
                            llm,
                            repo,
                            method,

                            integer(
                                    v(
                                            row,
                                            "global_method"
                                    )
                            ),

                            v(
                                    row,
                                    "function"
                            ),

                            filename,

                            v(
                                    row,
                                    "mapping_status"
                            ),

                            v(
                                    row,
                                    "target_preservation"
                            )
                    )
            );
        }

        return out;
    }

    private static List<GroundDep> readGroundTruth(
            Path file)
            throws Exception {

        CsvTable table =
                CsvTable.read(
                        file
                );

        List<GroundDep> out =
                new ArrayList<>();

        for (Map<String, String> row :
                table.rows) {

            out.add(
                    new GroundDep(
                            integer(
                                    v(
                                            row,
                                            "repo"
                                    )
                            ),

                            integer(
                                    v(
                                            row,
                                            "method"
                                    )
                            ),

                            integer(
                                    v(
                                            row,
                                            "global_method"
                                    )
                            ),

                            v(
                                    row,
                                    "function"
                            ),

                            upper(
                                    v(
                                            row,
                                            "kind"
                                    )
                            ),

                            v(
                                    row,
                                    "owner"
                            ),

                            v(
                                    row,
                                    "name"
                            ),

                            v(
                                    row,
                                    "signature"
                            ),

                            v(
                                    row,
                                    "provenance"
                            ),

                            integer(
                                    v(
                                            row,
                                            "depth"
                                    )
                            ),

                            v(
                                    row,
                                    "parent"
                            ),

                            v(
                                    row,
                                    "dependency_path",
                                    "path"
                            ),

                            v(
                                    row,
                                    "source"
                            ),

                            v(
                                    row,
                                    "resolution"
                            )
                    )
            );
        }

        return out;
    }

    private static boolean scoreType(
            String kind,
            String owner) {

        if (!upper(
                kind
        ).equals(
                "TYPE"
        )) {

            return true;
        }

        return !PRIMITIVES.contains(
                normalizePrimitive(
                        owner
                )
        );
    }

    private static String strictGeneratedIdentity(
            GenDep d) {

        return upper(
                d.kind()
        )
                + "|"
                + canonicalOwner(
                d.owner()
        )
                + "|"
                + normalizeMember(
                d.name()
        )
                + "|"
                + canonicalSignature(
                d.signature()
        );
    }

    private static String canonicalOwner(
            String value) {

        String s =
                normalize(
                        value
                )
                        .replace(
                                '$',
                                '.'
                        );

        if (s.startsWith("\"")
                && s.endsWith("\"")
                && s.length() >= 2) {

            return "java.lang.String";
        }

        int generic =
                s.indexOf('<');

        if (generic >= 0) {

            s =
                    s.substring(
                            0,
                            generic
                    );
        }

        while (s.endsWith(
                "[]"
        )) {

            s =
                    s.substring(
                            0,
                            s.length() - 2
                    );
        }

        return s.trim();
    }

    private static String simpleName(
            String value) {

        String s =
                canonicalOwner(
                        value
                );

        int dot =
                s.lastIndexOf('.');

        return dot >= 0
                ? s.substring(
                dot + 1
        )
                : s;
    }

    private static String canonicalSignature(
            String value) {

        return normalize(
                value
        )
                .replaceAll(
                        "\\s+",
                        ""
                );
    }

    private static String simpleSignature(
            String value) {

        return canonicalSignature(
                value
        )
                .replace(
                        '$',
                        '.'
                )
                .replaceAll(
                        "([A-Za-z_$][A-Za-z0-9_$]*\\.)+",
                        ""
                );
    }

    private static String normalizeMember(
            String value) {

        return normalize(
                value
        )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static boolean constructorMemberCompatible(
            String a,
            String b) {

        String x =
                normalizeMember(
                        a
                );

        String y =
                normalizeMember(
                        b
                );

        return x.equals(
                y
        )
                || x.equals(
                "<init>"
        )
                || y.equals(
                "<init>"
        )
                || x.isBlank()
                || y.isBlank();
    }

    private static boolean arityCompatible(
            String expectedSignature,
            String generatedSignature) {

        AritySpec e =
                aritySpec(
                        expectedSignature
                );

        AritySpec g =
                aritySpec(
                        generatedSignature
                );

        if (!e.known()
                || !g.known()) {

            return true;
        }

        if (e.varargs()
                && !g.varargs()) {

            return g.count()
                    >= e.minimum();
        }

        if (!e.varargs()
                && g.varargs()) {

            return e.count()
                    >= g.minimum();
        }

        if (e.varargs()
                && g.varargs()) {

            return true;
        }

        return e.count()
                == g.count();
    }

    private static AritySpec aritySpec(
            String signature) {

        String s =
                normalize(
                        signature
                );

        if (s.isBlank()) {

            return new AritySpec(
                    false,
                    -1,
                    false,
                    -1
            );
        }

        Matcher slash =
                Pattern.compile(
                        ".*/(\\d+)$"
                )
                        .matcher(
                                s
                        );

        if (slash.matches()) {

            int n =
                    integer(
                            slash.group(1)
                    );

            return new AritySpec(
                    true,
                    n,
                    false,
                    n
            );
        }

        int open =
                s.indexOf('(');

        int close =
                s.lastIndexOf(')');

        if (open < 0
                || close < open) {

            return new AritySpec(
                    false,
                    -1,
                    false,
                    -1
            );
        }

        String inside =
                s.substring(
                        open + 1,
                        close
                )
                        .trim();

        if (inside.isEmpty()) {

            return new AritySpec(
                    true,
                    0,
                    false,
                    0
            );
        }

        List<String> params =
                splitTopLevelParameters(
                        inside
                );

        boolean varargs =
                !params.isEmpty()
                        && params.get(
                                params.size() - 1
                        )
                        .contains("...");

        int count =
                params.size();

        int minimum =
                varargs
                        ? Math.max(
                        0,
                        count - 1
                )
                        : count;

        return new AritySpec(
                true,
                count,
                varargs,
                minimum
        );
    }

    private static List<String> splitTopLevelParameters(
            String inside) {

        List<String> out =
                new ArrayList<>();

        StringBuilder current =
                new StringBuilder();

        int angle =
                0;

        int square =
                0;

        int round =
                0;

        for (int i = 0;
             i < inside.length();
             i++) {

            char c =
                    inside.charAt(i);

            if (c == '<') {

                angle++;

            } else if (c == '>') {

                angle =
                        Math.max(
                                0,
                                angle - 1
                        );

            } else if (c == '[') {

                square++;

            } else if (c == ']') {

                square =
                        Math.max(
                                0,
                                square - 1
                        );

            } else if (c == '(') {

                round++;

            } else if (c == ')') {

                round =
                        Math.max(
                                0,
                                round - 1
                        );
            }

            if (c == ','
                    && angle == 0
                    && square == 0
                    && round == 0) {

                out.add(
                        current
                                .toString()
                                .trim()
                );

                current.setLength(
                        0
                );

            } else {

                current.append(
                        c
                );
            }
        }

        out.add(
                current
                        .toString()
                        .trim()
        );

        return out;
    }

    private static int arity(
            String signature) {

        AritySpec spec =
                aritySpec(
                        signature
                );

        return spec.known()
                ? spec.count()
                : -1;
    }

    private static String normalizePrimitive(
            String value) {

        return canonicalOwner(
                value
        )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static String targetClass(
            String function) {

        String s =
                normalize(
                        function
                );

        int dot =
                s.lastIndexOf('.');

        if (dot < 0) {

            return "";
        }

        String left =
                s.substring(
                        0,
                        dot
                );

        int last =
                left.lastIndexOf('.');

        return last >= 0
                ? left.substring(
                last + 1
        )
                : left;
    }

    private static String fileBaseWithoutJava(
            String file) {

        String f =
                normalizeFile(
                        file
                );

        if (f.endsWith(
                ".java"
        )) {

            f =
                    f.substring(
                            0,
                            f.length() - 5
                    );
        }

        return f;
    }

    private static boolean provenanceCompatible(
            String expected,
            String generated) {

        return provenanceClass(
                expected
        )
                .equals(
                        provenanceClass(
                                generated
                        )
                );
    }

    private static String provenanceClass(
            String value) {

        String p =
                upper(
                        value
                );

        if (p.startsWith(
                "EXTERNAL"
        )) {

            return "EXTERNAL";
        }

        if (p.equals(
                "JDK"
        )
                || p.startsWith(
                "JAVA_"
        )) {

            return "JDK";
        }

        if (p.startsWith(
                "REPOSITORY"
        )) {

            return "REPOSITORY";
        }

        if (p.startsWith(
                "GENERATED"
        )) {

            return "GENERATED";
        }

        return p;
    }

    private static String depLabel(
            GroundDep d) {

        return d.kind()
                + "|"
                + d.owner()
                + "|"
                + d.name()
                + "|"
                + d.signature();
    }

    private static String depLabel(
            GenDep d) {

        return d.kind()
                + "|"
                + d.owner()
                + "|"
                + d.name()
                + "|"
                + d.signature();
    }

    private static boolean isMethodLlmTxt(
            Path p) {

        if (p.getParent() == null
                || p.getParent()
                .getFileName()
                == null) {

            return false;
        }

        String parent =
                p.getParent()
                        .getFileName()
                        .toString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        String name =
                p.getFileName()
                        .toString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return parent.startsWith(
                "method_"
        )
                && name.endsWith(
                ".txt"
        )
                && LLMS.contains(
                name.substring(
                        0,
                        name.length() - 4
                )
        );
    }

    private static boolean isMethodLlmCsv(
            Path p) {

        if (p.getParent() == null
                || p.getParent()
                .getFileName()
                == null) {

            return false;
        }

        String parent =
                p.getParent()
                        .getFileName()
                        .toString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        String name =
                p.getFileName()
                        .toString()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return parent.startsWith(
                "method_"
        )
                && name.endsWith(
                ".csv"
        )
                && LLMS.contains(
                name.substring(
                        0,
                        name.length() - 4
                )
        );
    }

    private static int parseRepoFromMethodKey(
            String key) {

        try {

            return Integer.parseInt(
                    key.split(
                            ":",
                            2
                    )[0]
            );

        } catch (Exception e) {

            return 0;
        }
    }

    private static int parseMethodFromMethodKey(
            String key) {

        try {

            String[] parts =
                    key.split(
                            ":",
                            2
                    );

            return parts.length == 2
                    ? Integer.parseInt(
                    parts[1]
            )
                    : 0;

        } catch (Exception e) {

            return 0;
        }
    }

    private static void require(
            Path file) {

        if (!Files.isRegularFile(
                file
        )) {

            throw new IllegalStateException(
                    "Required input not found:\n"
                            + file
            );
        }
    }

    private static String v(
            Map<String, String> row,
            String... names) {

        for (String n :
                names) {

            String value =
                    row.get(
                            n
                    );

            if (value != null) {

                return value.trim();
            }
        }

        return "";
    }

    private static String normalizeFile(
            String value) {

        String s =
                normalize(
                        value
                )
                        .replace(
                                '\\',
                                '/'
                        );

        int slash =
                s.lastIndexOf('/');

        if (slash >= 0) {

            s =
                    s.substring(
                            slash + 1
                    );
        }

        return s.toLowerCase(
                Locale.ROOT
        );
    }

    private static String normalize(
            String value) {

        return value == null
                ? ""
                : value.trim();
    }

    private static String lower(
            String value) {

        return normalize(
                value
        )
                .toLowerCase(
                        Locale.ROOT
                );
    }

    private static String upper(
            String value) {

        return normalize(
                value
        )
                .toUpperCase(
                        Locale.ROOT
                );
    }

    private static int integer(
            String value) {

        try {

            return Integer.parseInt(
                    normalize(
                            value
                    )
            );

        } catch (Exception e) {

            return 0;
        }
    }

    private static double precision(
            int tp,
            int fp) {

        return tp + fp == 0
                ? 0
                : (double)
                tp
                / (tp + fp);
    }

    private static double recall(
            int tp,
            int fn) {

        return tp + fn == 0
                ? 0
                : (double)
                tp
                / (tp + fn);
    }

    private static double f1(
            double p,
            double r) {

        return p + r == 0
                ? 0
                : 2
                * p
                * r
                / (p + r);
    }

    private static String fmt(
            double value) {

        return String.format(
                Locale.ROOT,
                "%.6f",
                value
        );
    }

    private static String csv(
            Object... values) {

        StringBuilder b =
                new StringBuilder();

        for (int i = 0;
             i < values.length;
             i++) {

            if (i > 0) {

                b.append(',');
            }

            String s =
                    values[i] == null
                            ? ""
                            : String.valueOf(
                                    values[i]
                            );

            b.append('"')
                    .append(
                            s.replace(
                                    "\"",
                                    "\"\""
                            )
                    )
                    .append('"');
        }

        return b.toString();
    }

    private record MappingCandidate(
            Models.ManifestRow method,
            int score) {
    }

    private record MappingRow(
            String llm,
            int repo,
            int method,
            int globalMethod,
            String function,
            String file,
            String mappingStatus,
            String targetPreservation) {

        String observationKey() {

            return llm
                    + "|"
                    + repo
                    + ":"
                    + method;
        }
    }

    private record GroundDep(
            int repo,
            int method,
            int globalMethod,
            String function,
            String kind,
            String owner,
            String name,
            String signature,
            String provenance,
            int depth,
            String parent,
            String path,
            String source,
            String resolution) {
    }

    private record GenDep(
            String llm,
            String repoFolder,
            String file,
            int repo,
            int method,
            int globalMethod,
            String function,
            String kind,
            String owner,
            String name,
            String signature,
            String provenance,
            int depth,
            String parent,
            String path,
            String source,
            String resolution) {
    }

    private record Observation(
            String llm,
            int repo,
            int method,
            int globalMethod,
            String function,
            String file,
            String mappingStatus,
            String selectionReason,
            String targetPreservation,
            boolean missing) {

        String methodKey() {

            return repo
                    + ":"
                    + method;
        }

        String observationKey() {

            return llm
                    + "|"
                    + repo
                    + ":"
                    + method;
        }

        String fileKey() {

            return llm
                    + "|"
                    + normalizeFile(
                            file
                    );
        }
    }

    private record AritySpec(
            boolean known,
            int count,
            boolean varargs,
            int minimum) {
    }

    private record OwnerMatch(
            boolean compatible,
            int score,
            String type) {
    }

    private record Candidate(
            int expectedIndex,
            int generatedIndex,
            int score,
            String matchType) {
    }

    private record Decision(
            Observation observation,
            GroundDep expected,
            GenDep generated,
            String matchResult,
            String classification,
            boolean tp,
            boolean provenanceCorrect,
            String evidence) {
    }

    private record Extra(
            Observation observation,
            GenDep generated,
            String classification,
            String evidence) {
    }

    private record MatchOutput(
            List<Decision> decisions,
            List<Extra> extras) {
    }

    private record MethodScore(
            Observation observation,
            int rawGroundScoredCount,
            int groundCount,
            int rawGeneratedScoredCount,
            int generatedCount,
            int tp,
            int fp,
            int fn,
            double precision,
            double recall,
            double f1,
            int provenanceCorrect,
            int provenanceComparable,
            double provenanceAccuracy) {
    }

    private record MappingAuditRow(
            String llm,
            int repo,
            int method,
            String file,
            String status,
            String evidence) {
    }

    private record GeneratedAnalysisAuditRow(
            String llm,
            int repo,
            int method,
            String file,
            String status,
            int dependencyRows,
            String diagnostics) {
    }

    private record WarningRow(
            String severity,
            String llm,
            int repo,
            int method,
            String file,
            String code,
            String details) {
    }

    private record TargetSelfExclusion(
            String side,
            String llm,
            int repo,
            int method,
            String function,
            String file,
            String kind,
            String owner,
            String name,
            String signature,
            int depth,
            String path,
            String reason) {
    }

    private static final class Counts {

        int tp;
        int fp;
        int fn;
    }

    private static final class CsvTable {

        final List<Map<String, String>> rows;

        CsvTable(
                List<Map<String, String>> rows) {

            this.rows =
                    rows;
        }

        static CsvTable read(
                Path file)
                throws Exception {

            List<Map<String, String>> rows =
                    new ArrayList<>();

            try (BufferedReader reader =
                         Files.newBufferedReader(
                                 file,
                                 StandardCharsets.UTF_8
                         )) {

                String headerLine =
                        reader.readLine();

                if (headerLine == null) {

                    return new CsvTable(
                            rows
                    );
                }

                List<String> headers =
                        parseLine(
                                headerLine
                        );

                String line;

                while ((line =
                                reader.readLine())
                        != null) {

                    if (line.isBlank()) {

                        continue;
                    }

                    List<String> values =
                            parseLine(
                                    line
                            );

                    Map<String, String> row =
                            new LinkedHashMap<>();

                    for (int i = 0;
                         i < headers.size();
                         i++) {

                        row.put(
                                headers.get(i)
                                        .trim(),

                                i < values.size()
                                        ? values.get(i)
                                        : ""
                        );
                    }

                    rows.add(
                            row
                    );
                }
            }

            return new CsvTable(
                    rows
            );
        }

        private static List<String> parseLine(
                String line) {

            List<String> values =
                    new ArrayList<>();

            StringBuilder current =
                    new StringBuilder();

            boolean quoted =
                    false;

            for (int i = 0;
                 i < line.length();
                 i++) {

                char c =
                        line.charAt(i);

                if (c == '"') {

                    if (quoted
                            && i + 1
                            < line.length()
                            && line.charAt(
                            i + 1
                    ) == '"') {

                        current.append(
                                '"'
                        );

                        i++;

                    } else {

                        quoted =
                                !quoted;
                    }

                } else if (c == ','
                        && !quoted) {

                    values.add(
                            current.toString()
                    );

                    current.setLength(
                            0
                    );

                } else {

                    current.append(
                            c
                    );
                }
            }

            values.add(
                    current.toString()
            );

            return values;
        }
    }
}
