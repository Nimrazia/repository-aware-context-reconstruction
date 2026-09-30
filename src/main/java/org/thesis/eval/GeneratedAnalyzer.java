package org.thesis.eval;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class GeneratedAnalyzer {

    private GeneratedAnalyzer() {}

    static Models.Closure analyze(
            Models.Artifact artifact,
            Config cfg) throws Exception {

        Models.Closure out = new Models.Closure();
        out.method = artifact.mapped;

        if (artifact.mapped == null) {
            out.sourceStatus = "UNMAPPED";
            return out;
        }

        String text = Files.readString(
                artifact.path,
                StandardCharsets.UTF_8);

        JavaParser parser = new JavaParser(
                new ParserConfiguration()
                        .setLanguageLevel(
                                ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

        CompilationUnit cu = parser.parse(text)
                .getResult()
                .orElse(null);

        if (cu == null) {
            out.sourceStatus = "PARSE_FAILED";
            return out;
        }

        LocalIndex local = new LocalIndex(cu);

        TargetResolution resolved = findTarget(
                cu,
                text,
                artifact.mapped,
                artifact.filename);

        if (resolved == null || resolved.method() == null) {
            out.sourceStatus = "TARGET_NOT_FOUND";
            out.diagnostics.add(
                    "Could not uniquely resolve authoritative target method '"
                            + artifact.mapped.methodName()
                            + "' in generated file "
                            + artifact.filename);
            return out;
        }

        MethodDeclaration target = resolved.method();

        String owner = target.findAncestor(TypeDeclaration.class)
                .map(x -> x.getNameAsString())
                .orElse(CompileExecute.strip(artifact.filename));

        out.rootOwner = owner;
        out.rootSignature = target.getDeclarationAsString(false, false, true);
        out.sourceStatus = "RESOLVED";
        out.diagnostics.add(
                "TARGET_RESOLVER=" + resolved.reason()
                        + " | owner=" + owner
                        + " | method=" + target.getNameAsString()
                        + " | line="
                        + target.getBegin().map(p -> p.line).orElse(-1));

        Walker walker = new Walker(local, out, cfg);

        walker.addHierarchy(
                owner,
                0,
                "TARGET",
                artifact.mapped.methodName());

        walker.method(
                owner,
                target,
                0,
                "TARGET",
                artifact.mapped.methodName());

        return out;
    }

    private static TargetResolution findTarget(
            CompilationUnit cu,
            String originalSourceText,
            Models.ManifestRow manifest,
            String generatedFilename) {

        String targetName = manifest.methodName();

        List<MethodDeclaration> candidates = cu.findAll(MethodDeclaration.class)
                .stream()
                .filter(m -> m.getNameAsString().equals(targetName))

                .filter(m -> enclosingMethodDepth(m) == 0)
                .toList();

        if (candidates.isEmpty()) {
            return null;
        }

        if (candidates.size() == 1) {
            return new TargetResolution(
                    candidates.get(0),
                    "UNIQUE_AUTHORITATIVE_NAME");
        }

        List<LineRange> markerRanges = originalMethodMarkerRanges(originalSourceText);

        List<MethodDeclaration> marked = candidates.stream()
                .filter(m -> m.getBegin()
                        .map(p -> inAnyRange(p.line, markerRanges))
                        .orElse(false))
                .toList();

        if (marked.size() == 1) {
            return new TargetResolution(
                    marked.get(0),
                    "ORIGINAL_METHOD_MARKER");
        }

        String wrapper = CompileExecute.strip(generatedFilename);

        List<MethodDeclaration> wrapperMatches = candidates.stream()
                .filter(m -> sameSimpleName(enclosingSimpleTypeName(m), wrapper))
                .toList();

        if (wrapperMatches.size() == 1) {
            return new TargetResolution(
                    wrapperMatches.get(0),
                    "GENERATED_WRAPPER_CLASS");
        }

        String authoritativeClass = manifest.className();

        List<MethodDeclaration> ownerMatches = candidates.stream()
                .filter(m -> sameSimpleName(
                        enclosingSimpleTypeName(m),
                        authoritativeClass))
                .toList();

        if (ownerMatches.size() == 1) {
            return new TargetResolution(
                    ownerMatches.get(0),
                    "AUTHORITATIVE_DECLARING_CLASS");
        }

        List<MethodDeclaration> withBody = candidates.stream()
                .filter(m -> m.getBody().isPresent())
                .toList();

        if (withBody.size() == 1) {
            return new TargetResolution(
                    withBody.get(0),
                    "ONLY_CANDIDATE_WITH_BODY");
        }

        if (!withBody.isEmpty()) {
            candidates = withBody;
        }

        int minDepth = candidates.stream()
                .mapToInt(GeneratedAnalyzer::typeNestingDepth)
                .min()
                .orElse(Integer.MAX_VALUE);

        List<MethodDeclaration> shallowest = candidates.stream()
                .filter(m -> typeNestingDepth(m) == minDepth)
                .toList();

        if (shallowest.size() == 1) {
            return new TargetResolution(
                    shallowest.get(0),
                    "UNIQUE_SHALLOWEST_DECLARING_TYPE");
        }

        if (!shallowest.isEmpty()) {
            candidates = shallowest;
        }

        int maxArity = candidates.stream()
                .mapToInt(m -> m.getParameters().size())
                .max()
                .orElse(-1);

        List<MethodDeclaration> maxArityMatches = candidates.stream()
                .filter(m -> m.getParameters().size() == maxArity)
                .toList();

        if (maxArityMatches.size() == 1) {
            return new TargetResolution(
                    maxArityMatches.get(0),
                    "UNIQUE_MAX_ARITY_TIE_BREAK");
        }

        return null;
    }

    private static List<LineRange> originalMethodMarkerRanges(String text) {
        List<LineRange> ranges = new ArrayList<>();
        List<String> lines = text.lines().toList();

        Integer start = null;

        for (int i = 0; i < lines.size(); i++) {
            String x = lines.get(i).toUpperCase(Locale.ROOT);

            boolean originalMethod =
                    x.contains("ORIGINAL") && x.contains("METHOD");

            if (originalMethod && x.contains("START")) {

                start = i + 2;
            }

            if (start != null
                    && originalMethod
                    && x.contains("END")) {

                ranges.add(new LineRange(start, i));
                start = null;
            }
        }

        return ranges;
    }

    private static boolean inAnyRange(int line, List<LineRange> ranges) {
        for (LineRange r : ranges) {
            if (line >= r.start() && line <= r.end()) {
                return true;
            }
        }
        return false;
    }

    private static int enclosingMethodDepth(MethodDeclaration method) {
        int depth = 0;
        Node n = method;

        while ((n = n.getParentNode().orElse(null)) != null) {
            if (n instanceof MethodDeclaration) {
                depth++;
            }
        }

        return depth;
    }

    private static int typeNestingDepth(MethodDeclaration method) {
        int depth = 0;
        Node n = method;

        while ((n = n.getParentNode().orElse(null)) != null) {
            if (n instanceof TypeDeclaration<?>) {
                depth++;
            }
        }

        return depth;
    }

    private static String enclosingSimpleTypeName(MethodDeclaration method) {
        Node n = method;

        while ((n = n.getParentNode().orElse(null)) != null) {
            if (n instanceof TypeDeclaration<?> td) {
                return td.getNameAsString();
            }
        }

        return "";
    }

    private static boolean sameSimpleName(String a, String b) {
        String x = Models.Dep.simple(a == null ? "" : a);
        String y = Models.Dep.simple(b == null ? "" : b);
        return !x.isBlank() && x.equals(y);
    }

    static final class LocalIndex {
        final Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
        final Map<String, List<MethodDeclaration>> methods = new LinkedHashMap<>();
        final Map<String, List<ConstructorDeclaration>> ctors = new LinkedHashMap<>();
        final Map<String, FieldDeclaration> fields = new LinkedHashMap<>();

        LocalIndex(CompilationUnit cu) {
            for (TypeDeclaration<?> t : cu.getTypes()) {
                index(t, null);
            }
        }

        void index(TypeDeclaration<?> t, String parent) {
            String owner = parent == null
                    ? t.getNameAsString()
                    : parent + "." + t.getNameAsString();

            types.put(owner, t);

            for (MethodDeclaration m : t.getMethods()) {
                methods.computeIfAbsent(
                                methodKey(owner, m.getNameAsString(), m.getParameters().size()),
                                k -> new ArrayList<>())
                        .add(m);
            }

            for (ConstructorDeclaration c : t.getConstructors()) {
                ctors.computeIfAbsent(
                                methodKey(owner, "<init>", c.getParameters().size()),
                                k -> new ArrayList<>())
                        .add(c);
            }

            for (FieldDeclaration f : t.getFields()) {
                for (VariableDeclarator v : f.getVariables()) {
                    fields.put(
                            fieldKey(owner, v.getNameAsString()),
                            f);
                }
            }

            for (BodyDeclaration<?> b : t.getMembers()) {
                if (b instanceof TypeDeclaration<?> nested) {
                    index(nested, owner);
                }
            }
        }

        private String methodKey(String owner, String name, int arity) {
            return Models.Dep.simple(owner) + "#" + name + "/" + arity;
        }

        private String fieldKey(String owner, String name) {
            return Models.Dep.simple(owner) + "#" + name;
        }

        Optional<MethodDeclaration> method(String owner, String name, int arity) {
            List<MethodDeclaration> x = methods.get(methodKey(owner, name, arity));
            return x == null || x.size() != 1
                    ? Optional.empty()
                    : Optional.of(x.get(0));
        }

        Optional<ConstructorDeclaration> ctor(String owner, int arity) {
            List<ConstructorDeclaration> x = ctors.get(methodKey(owner, "<init>", arity));
            return x == null || x.size() != 1
                    ? Optional.empty()
                    : Optional.of(x.get(0));
        }

        Optional<FieldDeclaration> field(String owner, String name) {
            return Optional.ofNullable(fields.get(fieldKey(owner, name)));
        }

        boolean hasType(String owner) {
            String simple = Models.Dep.simple(owner);
            return types.keySet().stream()
                    .anyMatch(x -> Models.Dep.simple(x).equals(simple));
        }

        TypeDeclaration<?> type(String owner) {
            String simple = Models.Dep.simple(owner);
            return types.entrySet().stream()
                    .filter(e -> Models.Dep.simple(e.getKey()).equals(simple))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }

    static final class Walker {
        final LocalIndex li;
        final Models.Closure out;
        final Config cfg;
        final Set<String> visited = new HashSet<>();
        final Set<String> hierarchyVisited = new HashSet<>();

        Walker(LocalIndex li, Models.Closure out, Config cfg) {
            this.li = li;
            this.out = out;
            this.cfg = cfg;
        }

        void method(
                String owner,
                MethodDeclaration method,
                int depth,
                String parent,
                String path) {

            int maxDepth = cfg.integer("groundtruth.max.depth", 50);
            if (depth > maxDepth) {
                return;
            }

            String visitKey = "M|"
                    + owner + "|"
                    + method.getNameAsString() + "/"
                    + method.getParameters().size();

            if (!visited.add(visitKey)) {
                return;
            }

            Map<String, String> vars = new HashMap<>();

            for (Parameter p : method.getParameters()) {
                vars.put(p.getNameAsString(), p.getType().asString());
                type(p.getType(), depth, parent, path);
            }

            type(method.getType(), depth, parent, path);

            for (VariableDeclarator v : method.findAll(VariableDeclarator.class).stream()
                    .filter(x -> belongsToRoot(x, method))
                    .toList()) {
                vars.put(v.getNameAsString(), v.getType().asString());
                type(v.getType(), depth + 1, parent, path);
            }

            scan(owner, method, vars, depth, parent, path);
        }

        void ctor(
                String owner,
                ConstructorDeclaration ctor,
                int depth,
                String parent,
                String path) {

            int maxDepth = cfg.integer("groundtruth.max.depth", 50);
            if (depth > maxDepth) {
                return;
            }

            String key = "C|" + owner + "|" + ctor.getParameters().size();

            if (!visited.add(key)) {
                return;
            }

            Map<String, String> vars = new HashMap<>();

            for (Parameter p : ctor.getParameters()) {
                vars.put(p.getNameAsString(), p.getType().asString());
                type(p.getType(), depth, parent, path);
            }

            scan(owner, ctor, vars, depth, parent, path);
        }

        void field(
                String owner,
                FieldDeclaration field,
                int depth,
                String parent,
                String path) {

            for (VariableDeclarator v : field.getVariables()) {
                String key = "F|" + owner + "|" + v.getNameAsString();

                if (!visited.add(key)) {
                    continue;
                }

                type(v.getType(), depth + 1, parent, path);

                v.getInitializer().ifPresent(
                        x -> scan(
                                owner,
                                x,
                                new HashMap<>(),
                                depth + 1,
                                parent,
                                path));
            }
        }

        void scan(
                String owner,
                Node node,
                Map<String, String> vars,
                int depth,
                String parent,
                String path) {

            for (ClassOrInterfaceType t : node.findAll(ClassOrInterfaceType.class).stream()
                    .filter(x -> belongsToRoot(x, node))
                    .toList()) {
                type(t, depth + 1, parent, path);
            }

            for (MethodCallExpr mc : node.findAll(MethodCallExpr.class).stream()
                    .filter(x -> belongsToRoot(x, node))
                    .toList()) {
                String callOwner = infer(
                        mc.getScope().orElse(null),
                        owner,
                        vars);

                Models.Dep dep = new Models.Dep(
                        Models.Kind.METHOD,
                        callOwner,
                        mc.getNameAsString(),
                        mc.getNameAsString() + "/" + mc.getArguments().size(),
                        prov(callOwner),
                        depth + 1,
                        parent,
                        path + " -> "
                                + (callOwner.isBlank() ? "" : callOwner + ".")
                                + mc.getNameAsString(),
                        "",
                        "GENERATED_SYNTACTIC");

                out.add(dep);

                if (li.hasType(callOwner) || sameSimpleName(callOwner, owner)) {
                    li.method(
                                    callOwner,
                                    mc.getNameAsString(),
                                    mc.getArguments().size())
                            .ifPresent(x -> method(
                                    callOwner,
                                    x,
                                    depth + 1,
                                    dep.id(),
                                    dep.path));
                }
            }

            for (ObjectCreationExpr oc : node.findAll(ObjectCreationExpr.class).stream()
                    .filter(x -> belongsToRoot(x, node))
                    .toList()) {
                String constructed = oc.getType().getNameWithScope();

                Models.Dep dep = new Models.Dep(
                        Models.Kind.CONSTRUCTOR,
                        constructed,
                        "<init>",
                        "<init>/" + oc.getArguments().size(),
                        prov(constructed),
                        depth + 1,
                        parent,
                        path + " -> new " + constructed,
                        "",
                        "GENERATED_SYNTACTIC");

                out.add(dep);
                type(oc.getType(), depth + 1, parent, path);

                if (li.hasType(constructed)) {
                    li.ctor(constructed, oc.getArguments().size())
                            .ifPresent(x -> ctor(
                                    constructed,
                                    x,
                                    depth + 1,
                                    dep.id(),
                                    dep.path));
                }
            }

            for (FieldAccessExpr fa : node.findAll(FieldAccessExpr.class).stream()
                    .filter(x -> belongsToRoot(x, node))
                    .toList()) {
                String fieldOwner = infer(fa.getScope(), owner, vars);

                Models.Dep dep = new Models.Dep(
                        Models.Kind.FIELD,
                        fieldOwner,
                        fa.getNameAsString(),
                        "",
                        prov(fieldOwner),
                        depth + 1,
                        parent,
                        path + " -> " + fieldOwner + "." + fa.getNameAsString(),
                        "",
                        "GENERATED_SYNTACTIC");

                out.add(dep);

                if (li.hasType(fieldOwner)) {
                    li.field(fieldOwner, fa.getNameAsString())
                            .ifPresent(x -> field(
                                    fieldOwner,
                                    x,
                                    depth + 1,
                                    dep.id(),
                                    dep.path));
                }
            }

            for (NameExpr ne : node.findAll(NameExpr.class).stream()
                    .filter(x -> belongsToRoot(x, node))
                    .toList()) {
                String name = ne.getNameAsString();

                if (vars.containsKey(name)) {
                    continue;
                }

                li.field(owner, name).ifPresent(f -> {
                    Models.Dep dep = new Models.Dep(
                            Models.Kind.FIELD,
                            owner,
                            name,
                            "",
                            Models.Provenance.GENERATED,
                            depth + 1,
                            parent,
                            path + " -> " + owner + "." + name,
                            "",
                            "LOCAL_FIELD");

                    out.add(dep);
                    field(owner, f, depth + 1, dep.id(), dep.path);
                });
            }

            for (AnnotationExpr an : node.findAll(AnnotationExpr.class).stream()
                    .filter(x -> belongsToRoot(x, node))
                    .toList()) {
                String annotation = an.getNameAsString();

                out.add(new Models.Dep(
                        Models.Kind.ANNOTATION,
                        annotation,
                        annotation,
                        annotation,
                        prov(annotation),
                        depth + 1,
                        parent,
                        path + " -> @" + annotation,
                        "",
                        "GENERATED_SYNTACTIC"));
            }
        }

        void type(
                Type type,
                int depth,
                String parent,
                String path) {

            if (type == null) {
                return;
            }

            List<ClassOrInterfaceType> refs =
                    type.findAll(ClassOrInterfaceType.class);

            if (!refs.isEmpty()) {
                Set<String> seen = new LinkedHashSet<>();
                for (ClassOrInterfaceType ref : refs) {
                    String q = SourceIndex.raw(ref.getNameWithScope());
                    if (q != null && !q.isBlank() && seen.add(q)) {
                        typeText(q, depth, parent, path);
                    }
                }
                return;
            }

            typeText(SourceIndex.raw(type.asString()), depth, parent, path);
        }

        void typeText(
                String q,
                int depth,
                String parent,
                String path) {

            if (q == null) {
                return;
            }

            q = q.trim();
            if (q.isBlank() || primitive(q)) {
                return;
            }

            Models.Dep dep = new Models.Dep(
                    Models.Kind.TYPE,
                    q,
                    "",
                    q,
                    prov(q),
                    depth,
                    parent,
                    path + " -> " + q,
                    "",
                    "GENERATED_TYPE");

            out.add(dep);

            if (li.hasType(q)) {
                addHierarchy(q, depth, dep.id(), dep.path);
            }
        }

        Models.Provenance prov(String owner) {
            String s = SourceIndex.raw(owner);

            if (s.startsWith("java.")
                    || s.startsWith("javax.")
                    || s.startsWith("jdk.")
                    || Set.of(
                                    "String", "Object", "System", "Math",
                                    "Integer", "Long", "Boolean", "Double",
                                    "Float", "Short", "Byte", "Character",
                                    "Exception", "RuntimeException",
                                    "IllegalArgumentException", "IOException",
                                    "NoSuchMethodError", "List", "Map", "Set",
                                    "Collection", "ArrayList", "HashMap",
                                    "HashSet", "TreeSet", "Stack", "Queue",
                                    "Deque", "Optional")
                            .contains(Models.Dep.simple(s))) {

                return Models.Provenance.JDK;
            }

            return Models.Provenance.GENERATED;
        }

        String infer(
                Expression expression,
                String owner,
                Map<String, String> vars) {

            if (expression == null || expression.isThisExpr()) {
                return owner;
            }

            if (expression.isNameExpr()) {
                String name = expression.asNameExpr().getNameAsString();

                if (vars.containsKey(name)) {
                    return vars.get(name);
                }

                if (li.hasType(name)) {
                    return name;
                }

                Optional<FieldDeclaration> field = li.field(owner, name);

                if (field.isPresent()) {
                    for (VariableDeclarator v : field.get().getVariables()) {
                        if (v.getNameAsString().equals(name)) {
                            return v.getType().asString();
                        }
                    }
                }

                return name;
            }

            if (expression.isObjectCreationExpr()) {
                return expression.asObjectCreationExpr().getType().asString();
            }

            if (expression.isFieldAccessExpr()) {
                return infer(
                        expression.asFieldAccessExpr().getScope(),
                        owner,
                        vars);
            }

            return expression.toString();
        }

        void addHierarchy(
                String owner,
                int depth,
                String parent,
                String path) {

            String key = Models.Dep.simple(owner);

            if (!hierarchyVisited.add(key)) {
                return;
            }

            TypeDeclaration<?> type = li.type(owner);

            if (!(type instanceof ClassOrInterfaceDeclaration c)) {
                return;
            }

            for (ClassOrInterfaceType x : c.getExtendedTypes()) {
                String name = x.asString();

                out.add(new Models.Dep(
                        Models.Kind.SUPERCLASS,
                        name,
                        "",
                        name,
                        prov(name),
                        depth + 1,
                        parent,
                        path + " -> extends " + name,
                        "",
                        "GENERATED_HIERARCHY"));
            }

            for (ClassOrInterfaceType x : c.getImplementedTypes()) {
                String name = x.asString();

                out.add(new Models.Dep(
                        Models.Kind.INTERFACE,
                        name,
                        "",
                        name,
                        prov(name),
                        depth + 1,
                        parent,
                        path + " -> implements " + name,
                        "",
                        "GENERATED_HIERARCHY"));
            }
        }

        private static boolean primitive(String q) {
            return Set.of(
                            "void", "boolean", "byte", "short", "int",
                            "long", "float", "double", "char", "var")
                    .contains(q);
        }
    }

    private static boolean belongsToRoot(Node child, Node root) {
        if (child == root) {
            return true;
        }

        Node n = child;
        while ((n = n.getParentNode().orElse(null)) != null) {
            if (n == root) {
                return true;
            }
            if (n instanceof MethodDeclaration
                    || n instanceof ConstructorDeclaration
                    || n instanceof TypeDeclaration<?>) {
                return false;
            }
        }
        return false;
    }

    static MethodDeclaration findTarget(
            CompilationUnit cu,
            Models.ManifestRow manifest) {

        TargetResolution r = findTarget(
                cu,
                cu == null ? "" : cu.toString(),
                manifest,
                "");

        return r == null ? null : r.method();
    }

    static String norm(Node n) {
        if (n == null) {
            return "";
        }
        return n.toString()
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//.*?(\\R|$)", "")
                .replaceAll("\\s+", "");
    }

    private record LineRange(int start, int end) {}

    private record TargetResolution(
            MethodDeclaration method,
            String reason) {}
}
