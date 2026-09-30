package org.thesis.eval;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class RepositoryTargetLocator {

    private RepositoryTargetLocator() {}

    static MethodDeclaration findRepo(
            SourceIndex idx,
            Models.ManifestRow m) throws Exception {

        if (idx == null) {
            return null;
        }

        Path p = idx.repo.resolve(m.sourceFile());

        if (!Files.exists(p)) {
            return null;
        }

        CompilationUnit cu = idx.parser.parse(p)
                .getResult()
                .orElse(null);

        if (cu == null) {
            return null;
        }

        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class)
                .stream()
                .filter(x -> x.getNameAsString().equals(m.methodName()))
                .toList();

        if (methods.size() == 1) {
            return methods.get(0);
        }

        for (MethodDeclaration x : methods) {
            if (x.getRange().isPresent()
                    && m.lineStart() >= x.getRange().get().begin.line
                    && m.lineStart() <= x.getRange().get().end.line) {
                return x;
            }
        }

        return null;
    }
}
