package com.brucecli.rag.graph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.brucecli.rag.model.CodeRelation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AST 代码关系分析器。
 */
public class CodeAnalyzer {
    private final Path projectRoot;
    private final JavaParser javaParser;

    public CodeAnalyzer(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(configuration);
    }

    public List<CodeRelation> analyzeFile(Path filePath) throws IOException {
        Path absolutePath = filePath.toAbsolutePath().normalize();
        if (!absolutePath.toString().endsWith(".java")) {
            return List.of();
        }

        String content = Files.readString(absolutePath, StandardCharsets.UTF_8);
        Optional<CompilationUnit> parsed = javaParser.parse(content).getResult();
        if (parsed.isEmpty()) {
            return List.of();
        }

        String relativePath = toRelativePath(absolutePath);
        List<CodeRelation> relations = new ArrayList<>();
        extractImports(relativePath, parsed.get(), relations);
        extractClassRelations(relativePath, parsed.get(), relations);
        return relations;
    }

    private void extractImports(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        cu.getImports().forEach(importDecl -> {
            String importName = importDecl.getNameAsString();
            if (importName.startsWith("java.") || importName.startsWith("javax.")) {
                return;
            }
            String unitName = cu.getPrimaryTypeName().orElse(filePath);
            relations.add(CodeRelation.of(projectPath(), filePath, unitName, "", importName, "imports"));
        });
    }

    private void extractClassRelations(String filePath, CompilationUnit cu, List<CodeRelation> relations) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = clazz.getNameAsString();

            clazz.getExtendedTypes().forEach(ext -> relations.add(CodeRelation.of(
                projectPath(), filePath, className, "", ext.getNameAsString(), "extends"
            )));

            clazz.getImplementedTypes().forEach(impl -> relations.add(CodeRelation.of(
                projectPath(), filePath, className, "", impl.getNameAsString(), "implements"
            )));

            clazz.getMethods().forEach(method -> relations.add(CodeRelation.of(
                projectPath(), filePath, className, filePath, className + "." + method.getNameAsString(), "contains"
            )));

            clazz.findAll(MethodCallExpr.class).forEach(call -> {
                Optional<MethodDeclaration> parentMethod = findParentMethod(call);
                if (parentMethod.isPresent()) {
                    String caller = className + "." + parentMethod.get().getNameAsString();
                    relations.add(CodeRelation.of(
                        projectPath(), filePath, caller, "", call.getNameAsString(), "calls"
                    ));
                }
            });
        });
    }

    private Optional<MethodDeclaration> findParentMethod(Node node) {
        Optional<Node> current = node.getParentNode();
        while (current.isPresent()) {
            Node parent = current.get();
            if (parent instanceof MethodDeclaration method) {
                return Optional.of(method);
            }
            current = parent.getParentNode();
        }
        return Optional.empty();
    }

    private String toRelativePath(Path absolutePath) {
        if (absolutePath.startsWith(projectRoot)) {
            return projectRoot.relativize(absolutePath).toString();
        }
        return absolutePath.toString();
    }

    private String projectPath() {
        return projectRoot.toString();
    }
}
