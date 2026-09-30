package org.thesis.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class RepoManager {

    static Map<Integer, Path> prepare(
            Config cfg,
            List<Models.ManifestRow> manifest) throws Exception {

        Map<Integer, Models.ManifestRow> one = new TreeMap<>();

        for (Models.ManifestRow m : manifest) {
            one.putIfAbsent(m.repoIndex(), m);
        }

        Map<Integer, Path> out = new LinkedHashMap<>();

        Path root = cfg.path("workspace")
                .resolve("repositories");

        Files.createDirectories(root);

        int current = 0;

        for (Map.Entry<Integer, Models.ManifestRow> entry : one.entrySet()) {

            current++;

            Models.ManifestRow m = entry.getValue();

            Path dir = root.resolve(
                    String.format("repo%02d", m.repoIndex())
            );

            System.out.printf(
                    "%n[REPO %d/%d] %s @ %s%n",
                    current,
                    one.size(),
                    m.repoSlug(),
                    shortCommit(m.commit())
            );

            ensure(dir, m, cfg);

            out.put(entry.getKey(), dir);
        }

        return out;
    }

    private static void ensure(
            Path dir,
            Models.ManifestRow m,
            Config cfg) throws Exception {

        String git = ProcessUtil.findExecutable("git");

        String url =
                "https://github.com/" + m.repoSlug() + ".git";

        int retries =
                Math.max(3, cfg.integer("clone.retry.count", 5));

        if (Files.exists(dir.resolve(".git"))) {

            System.out.println("Existing clone found.");

            if (isValidGitRepository(git, dir)) {

                if (checkoutCommit(git, dir, m.commit(), cfg)) {

                    verifyHead(git, dir, m);

                    System.out.println(
                            "Repository ready from cache: "
                                    + m.repoSlug()
                    );

                    return;
                }

                System.out.println(
                        "Required commit not available in cached clone."
                );

                System.out.println(
                        "Will fetch the exact commit."
                );

            } else {

                System.out.println(
                        "Existing repository directory is incomplete/corrupt."
                );

                System.out.println(
                        "Removing broken clone..."
                );

                delete(dir);
            }
        }

        if (!Files.exists(dir.resolve(".git"))) {

            boolean cloned = false;

            for (int attempt = 1; attempt <= retries; attempt++) {

                System.out.printf(
                        "Clone attempt %d/%d: %s%n",
                        attempt,
                        retries,
                        m.repoSlug()
                );

                delete(dir);

                Files.createDirectories(dir.getParent());

                List<String> command = new ArrayList<>();

                command.add(git);

                command.add("-c");
                command.add("http.version=HTTP/1.1");

                command.add("-c");
                command.add("http.lowSpeedLimit=0");

                command.add("-c");
                command.add("http.lowSpeedTime=999999");

                command.add("-c");
                command.add("core.compression=0");

                command.add("clone");

                command.add("--no-checkout");

                command.add("--filter=blob:none");

                command.add(url);
                command.add(dir.toString());

                var result = ProcessUtil.run(
                        command,
                        dir.getParent(),
                        cfg.integer("clone.timeout.seconds", 1800)
                );

                if (result.exit() == 0
                        && Files.exists(dir.resolve(".git"))
                        && isValidGitRepository(git, dir)) {

                    cloned = true;

                    System.out.println(
                            "Clone successful."
                    );

                    break;
                }

                System.out.println(
                        "Clone attempt failed."
                );

                if (!result.err().isBlank()) {
                    System.out.println(result.err());
                }

                if (attempt < retries) {

                    long sleepMs = Math.min(
                            30_000L,
                            3000L * attempt
                    );

                    System.out.println(
                            "Retrying after "
                                    + (sleepMs / 1000)
                                    + " seconds..."
                    );

                    Thread.sleep(sleepMs);
                }
            }

            if (!cloned) {

                throw new IllegalStateException(
                        "Clone failed after "
                                + retries
                                + " attempts for "
                                + m.repoSlug()
                );
            }
        }

        if (!checkoutCommit(git, dir, m.commit(), cfg)) {

            System.out.println(
                    "Commit not currently available locally."
            );

            boolean fetched = fetchExactCommit(
                    git,
                    dir,
                    m.commit(),
                    cfg,
                    retries
            );

            if (!fetched) {

                System.out.println(
                        "Exact-commit fetch failed."
                );

                System.out.println(
                        "Trying normal origin fetch..."
                );

                List<String> fetch = List.of(
                        git,
                        "-c",
                        "http.version=HTTP/1.1",
                        "-c",
                        "http.lowSpeedLimit=0",
                        "fetch",
                        "--prune",
                        "origin"
                );

                var fr = ProcessUtil.run(
                        fetch,
                        dir,
                        cfg.integer("clone.timeout.seconds", 1800)
                );

                if (fr.exit() != 0) {

                    System.out.println(
                            "Normal fetch warning:"
                    );

                    System.out.println(fr.err());
                }
            }
        }

        boolean checkout = checkoutCommit(
                git,
                dir,
                m.commit(),
                cfg
        );

        if (!checkout) {

            throw new IllegalStateException(
                    "Checkout failed for "
                            + m.repoSlug()
                            + " at commit "
                            + m.commit()
            );
        }

        verifyHead(git, dir, m);

        System.out.println(
                "Repository ready: "
                        + m.repoSlug()
                        + " @ "
                        + shortCommit(m.commit())
        );
    }

    private static boolean fetchExactCommit(
            String git,
            Path dir,
            String commit,
            Config cfg,
            int retries) throws Exception {

        for (int attempt = 1; attempt <= retries; attempt++) {

            System.out.printf(
                    "Fetch exact commit attempt %d/%d: %s%n",
                    attempt,
                    retries,
                    shortCommit(commit)
            );

            List<String> command = List.of(
                    git,
                    "-c",
                    "http.version=HTTP/1.1",
                    "-c",
                    "http.lowSpeedLimit=0",
                    "-c",
                    "http.lowSpeedTime=999999",
                    "fetch",
                    "--no-tags",
                    "origin",
                    commit
            );

            var result = ProcessUtil.run(
                    command,
                    dir,
                    cfg.integer("clone.timeout.seconds", 1800)
            );

            if (result.exit() == 0) {
                return true;
            }

            if (!result.err().isBlank()) {
                System.out.println(result.err());
            }

            if (attempt < retries) {

                long sleepMs = Math.min(
                        30_000L,
                        3000L * attempt
                );

                Thread.sleep(sleepMs);
            }
        }

        return false;
    }

    private static boolean checkoutCommit(
            String git,
            Path dir,
            String commit,
            Config cfg) throws Exception {

        if (!Files.exists(dir.resolve(".git"))) {
            return false;
        }

        var exists = ProcessUtil.run(
                List.of(
                        git,
                        "cat-file",
                        "-e",
                        commit + "^{commit}"
                ),
                dir,
                60
        );

        if (exists.exit() != 0) {
            return false;
        }

        var checkout = ProcessUtil.run(
                List.of(
                        git,
                        "checkout",
                        "--force",
                        "--detach",
                        commit
                ),
                dir,
                cfg.integer("checkout.timeout.seconds", 300)
        );

        if (checkout.exit() != 0) {

            System.out.println(
                    "Checkout error:"
            );

            System.out.println(checkout.err());

            return false;
        }

        return true;
    }

    private static boolean isValidGitRepository(
            String git,
            Path dir) throws Exception {

        var result = ProcessUtil.run(
                List.of(
                        git,
                        "rev-parse",
                        "--is-inside-work-tree"
                ),
                dir,
                30
        );

        return result.exit() == 0
                && result.out()
                .trim()
                .equalsIgnoreCase("true");
    }

    private static void verifyHead(
            String git,
            Path dir,
            Models.ManifestRow m) throws Exception {

        var head = ProcessUtil.run(
                List.of(
                        git,
                        "rev-parse",
                        "HEAD"
                ),
                dir,
                30
        );

        if (head.exit() != 0) {

            throw new IllegalStateException(
                    "Unable to read HEAD for "
                            + m.repoSlug()
                            + "\n"
                            + head.err()
            );
        }

        String actual =
                head.out().trim();

        if (!actual.equalsIgnoreCase(m.commit())) {

            throw new IllegalStateException(
                    "Commit verification failed for "
                            + m.repoSlug()
                            + "\nExpected: "
                            + m.commit()
                            + "\nActual:   "
                            + actual
            );
        }

        System.out.println(
                "Commit verification: PASS"
        );
    }

    private static String shortCommit(String commit) {

        if (commit == null) {
            return "";
        }

        return commit.substring(
                0,
                Math.min(12, commit.length())
        );
    }

    static void delete(Path p) throws Exception {

        if (!Files.exists(p)) {
            return;
        }

        try (var stream = Files.walk(p)) {

            for (Path x :
                    stream.sorted(
                            Comparator.reverseOrder()
                    ).toList()) {

                try {
                    x.toFile().setWritable(true);
                } catch (Exception ignored) {
                }

                Files.deleteIfExists(x);
            }
        }
    }
}
