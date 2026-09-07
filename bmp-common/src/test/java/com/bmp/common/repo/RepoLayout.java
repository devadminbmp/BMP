package com.bmp.common.repo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where things are on disk, for the repo-hygiene tests in this package.
 *
 * <h2>Why these checks are JUnit tests and not scripts</h2>
 * They used to be two Python files under {@code scripts/} plus two inline {@code python3}
 * heredocs in the CI workflow. That worked, but it put four repo-wide invariants in a language
 * nothing else in this repo is written in, in a place the build never looks. Three costs:
 *
 * <ul>
 *   <li><b>They only ran in CI.</b> You found out you'd broken one after pushing, not while
 *       writing the code — and the fix arrived as a red tick on a branch rather than a failing
 *       test in your IDE.</li>
 *   <li><b>Nobody could run them without knowing they existed.</b> {@code mvn verify} is the
 *       thing everyone already types. A check that isn't attached to it is opt-in, and opt-in
 *       checks decay.</li>
 *   <li><b>A separate toolchain to keep alive</b> — a Python version, a YAML library, and a
 *       second set of conventions, for a team that writes Java and TypeScript.</li>
 * </ul>
 *
 * As tests they run on every build, locally and in CI, fail with a normal assertion, and can be
 * debugged with the same tools as everything else.
 *
 * <h2>The trade-off, stated plainly</h2>
 * These read the source tree, so they're coupled to the repo's layout — move a folder and they
 * break. That is deliberate. Each one encodes a rule that was learned from a real incident, and
 * a rule enforced against the actual files is worth more than a rule written in a document
 * nobody re-reads.
 */
final class RepoLayout {

    private RepoLayout() {}

    /**
     * The BMP backend repo root.
     *
     * <p>Surefire runs with the working directory set to the MODULE, so from bmp-common the root
     * is one level up. Rather than assume that, walk upward until we find the aggregator pom —
     * which keeps this working if a test is ever run from somewhere else, and fails with a clear
     * message instead of a confusing empty result set if it can't.
     */
    static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            Path pom = p.resolve("pom.xml");
            // Every child module's <parent> block also names bmp-platform, so that substring
            // alone matches the module's own pom (e.g. bmp-common's) before ever walking up.
            // packaging=pom is only true of the aggregator itself.
            if (Files.isRegularFile(pom)) {
                String content = read(pom);
                if (content.contains("<artifactId>bmp-platform</artifactId>")
                        && content.contains("<packaging>pom</packaging>")) {
                    return p;
                }
            }
        }
        throw new IllegalStateException(
                "Could not find the repo root (the aggregator pom, packaging=pom, artifactId "
                + "bmp-platform) walking up from " + dir + ". These tests read the source tree; "
                + "if the layout moved, they need updating rather than deleting.");
    }

    /** Every Maven module directory that has a {@code src/main}. */
    static List<Path> serviceDirs() {
        try (Stream<Path> s = Files.list(root())) {
            return s.filter(Files::isDirectory)
                    .filter(p -> Files.isDirectory(p.resolve("src/main")))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every file under {@code dir} matching a filename suffix, recursively. */
    static List<Path> filesEndingWith(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + p, e);
        }
    }

    /** Path relative to the repo root, for readable failure messages. */
    static String rel(Path p) {
        return root().relativize(p.toAbsolutePath()).toString().replace('\\', '/');
    }
}
