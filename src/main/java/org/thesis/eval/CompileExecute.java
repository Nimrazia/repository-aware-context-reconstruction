package org.thesis.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

final class CompileExecute {
  static void run(Config cfg, List<Models.Artifact> arts) throws Exception {
    Path work = cfg.path("workspace").resolve("generated-runs");
    Files.createDirectories(work);

    int batchSize = Math.max(1, cfg.integer("compile.batch.size", 25));
    int total = arts.size();
    int batches = (total + batchSize - 1) / batchSize;

    for (int b = 0; b < batches; b++) {
      int from = b * batchSize;
      int to = Math.min(total, from + batchSize);
      List<Models.Artifact> batch = arts.subList(from, to);

      System.out.printf("%n============================================================%n");
      System.out.printf("COMPILATION/EXECUTION BATCH %d/%d  files %d-%d of %d%n", b + 1, batches, from + 1, to, total);
      System.out.printf("============================================================%n");

      for (int i = from; i < to; i++) {
        Models.Artifact a = arts.get(i);
        System.out.printf("[FILE %d/%d] %s/%s%n", i + 1, total, a.llm, a.filename);
        Path dir = work.resolve(a.llm).resolve(a.repoFolder).resolve(strip(a.filename));
        delete(dir);
        Files.createDirectories(dir);
        Path src = dir.resolve(a.filename);
        Files.copy(a.path, src, StandardCopyOption.REPLACE_EXISTING);
        compile(cfg, a, dir, src);
        if (a.compilePass && cfg.bool("run.execute", true)) execute(cfg, a, dir, src);
      }

      writeBatchReport(cfg, batch, b + 1, batches, from + 1, to, total);
      long cp = batch.stream().filter(a -> a.compilePass).count();
      long ep = batch.stream().filter(a -> a.executePass).count();
      System.out.printf("Batch %d complete: compilation %d/%d PASS, execution %d/%d PASS%n", b + 1, cp, batch.size(), ep, batch.size());

      System.gc();
      if (cfg.integer("compile.batch.pause.ms", 100) > 0) {
        Thread.sleep(cfg.integer("compile.batch.pause.ms", 100));
      }
    }
  }

  static void compile(Config cfg, Models.Artifact a, Path dir, Path src) throws Exception {
    List<String> c = new ArrayList<>(List.of(
        ProcessUtil.findExecutable("javac"), "-encoding", "UTF-8", "-d", dir.toString(), src.getFileName().toString()));
    var r = ProcessUtil.run(c, dir, cfg.integer("compile.timeout.seconds", 120));

    if (r.exit() != 0 && cfg.bool("compile.retry.javac.exports", true)) {
      List<String> c2 = new ArrayList<>(List.of(
          ProcessUtil.findExecutable("javac"), "-encoding", "UTF-8",
          "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
          "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
          "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
          "-d", dir.toString(), src.getFileName().toString()));
      var r2 = ProcessUtil.run(c2, dir, cfg.integer("compile.timeout.seconds", 120));
      a.compileOut = r.out() + "\n--- RETRY WITH JDK EXPORTS ---\n" + r2.out();
      a.compileErr = r.err() + "\n--- RETRY WITH JDK EXPORTS ---\n" + r2.err();
      a.compileExit = r2.exit();
      a.compileMs = r.ms() + r2.ms();
      a.compilePass = r2.exit() == 0;
      a.compileStatus = r2.timeout() ? "TIMEOUT" : a.compilePass ? "PASS" : "FAIL";
    } else {
      a.compileOut = r.out();
      a.compileErr = r.err();
      a.compileExit = r.exit();
      a.compileMs = r.ms();
      a.compilePass = r.exit() == 0;
      a.compileStatus = r.timeout() ? "TIMEOUT" : a.compilePass ? "PASS" : "FAIL";
    }
  }

  static void execute(Config cfg, Models.Artifact a, Path dir, Path src) throws Exception {
    String text = Files.readString(src, StandardCharsets.UTF_8);
    String pkg = "";
    Matcher pm = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
    if (pm.find()) pkg = pm.group(1);
    String cls = publicClass(text);
    if (cls.isBlank()) cls = strip(src.getFileName().toString());
    a.mainClass = pkg.isBlank() ? cls : pkg + "." + cls;

    List<String> c = new ArrayList<>(List.of(
        ProcessUtil.findExecutable("java"),
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "-cp", dir.toString(), a.mainClass));
    var r = ProcessUtil.run(c, dir, cfg.integer("execute.timeout.seconds", 90));
    a.executeOut = r.out();
    a.executeErr = r.err();
    a.executeExit = r.exit();
    a.executeMs = r.ms();
    a.executePass = r.exit() == 0;
    a.executionStatus = r.timeout() ? "TIMEOUT" : a.executePass ? "PASS" : "FAIL";
  }

  private static void writeBatchReport(Config cfg, List<Models.Artifact> batch, int batchNo, int batches,
                                       int from, int to, int total) throws Exception {
    Path out = cfg.path("results").resolve("compilation_execution/batches");
    Files.createDirectories(out);
    List<List<?>> rows = new ArrayList<>();
    StringBuilder txt = new StringBuilder();
    txt.append("COMPILATION / EXECUTION BATCH REPORT\n")
       .append("====================================\n\n")
       .append("Batch: ").append(batchNo).append(" / ").append(batches).append('\n')
       .append("Global file range: ").append(from).append("-").append(to).append(" / ").append(total).append("\n\n");

    for (Models.Artifact a : batch) {
      rows.add(List.of(a.llm, a.repoFolder, a.filename,
          a.mapped == null ? "" : a.mapped.key(),
          a.compileStatus, a.compileExit, a.compileMs,
          a.executionStatus, a.executeExit, a.executeMs,
          a.mainClass, clean(a.compileOut), clean(a.compileErr), clean(a.executeOut), clean(a.executeErr)));

      txt.append("FILE: ").append(a.llm).append('/').append(a.repoFolder).append('/').append(a.filename).append('\n')
         .append("Compilation: ").append(a.compileStatus).append(" | exit=").append(a.compileExit).append(" | ms=").append(a.compileMs).append('\n')
         .append("Execution: ").append(a.executionStatus).append(" | exit=").append(a.executeExit).append(" | ms=").append(a.executeMs).append('\n')
         .append("--- COMPILE STDOUT ---\n").append(a.compileOut).append('\n')
         .append("--- COMPILE STDERR ---\n").append(a.compileErr).append('\n')
         .append("--- EXECUTION STDOUT ---\n").append(a.executeOut).append('\n')
         .append("--- EXECUTION STDERR ---\n").append(a.executeErr).append("\n\n");
    }

    List<String> header = List.of("llm","repo","file","method_key","compile_status","compile_exit","compile_ms",
        "execution_status","execution_exit","execution_ms","main_class","compile_stdout","compile_stderr","execution_stdout","execution_stderr");
    String base = String.format(Locale.ROOT, "batch_%03d", batchNo);
    CsvUtil.write(out.resolve(base + ".csv"), header, rows);
    Files.writeString(out.resolve(base + ".txt"), txt.toString(), StandardCharsets.UTF_8);
  }

  private static String clean(String s) {
    if (s == null) return "";
    return s.length() > 20000 ? s.substring(0, 20000) + "...[TRUNCATED IN CSV; FULL TEXT IN TXT]" : s;
  }

  static String publicClass(String s) {
    Matcher m = Pattern.compile("\\bpublic\\s+(?:final\\s+)?class\\s+(\\w+)").matcher(s);
    return m.find() ? m.group(1) : "";
  }

  static String strip(String s) {
    int i = s.lastIndexOf('.');
    return i < 0 ? s : s.substring(0, i);
  }

  static void delete(Path p) throws Exception {
    if (!Files.exists(p)) return;
    try (var s = Files.walk(p)) {
      for (Path x : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
    }
  }
}
