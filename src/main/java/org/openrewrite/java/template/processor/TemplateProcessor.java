/*
 * Copyright 2022 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.template.processor;

import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import org.openrewrite.java.template.internal.ClasspathJarNameDetector;
import org.openrewrite.java.template.internal.ImportDetector;
import org.openrewrite.java.template.internal.JavacResolution;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.*;

/**
 * For steps to debug this annotation processor, see
 * <a href="https://medium.com/@joachim.beckers/debugging-an-annotation-processor-using-intellij-idea-in-2018-cde72758b78a">this blog post</a>.
 */
@SupportedAnnotationTypes("*")
public class TemplateProcessor extends TypeAwareProcessor {
    private static final String PRIMITIVE_ANNOTATION = "org.openrewrite.java.template.Primitive";
    private static final Map<String, String> PRIMITIVE_TYPE_MAP = new HashMap<>();

    static {
        PRIMITIVE_TYPE_MAP.put(Boolean.class.getName(), boolean.class.getName());
        PRIMITIVE_TYPE_MAP.put(Byte.class.getName(), byte.class.getName());
        PRIMITIVE_TYPE_MAP.put(Character.class.getName(), char.class.getName());
        PRIMITIVE_TYPE_MAP.put(Short.class.getName(), short.class.getName());
        PRIMITIVE_TYPE_MAP.put(Integer.class.getName(), int.class.getName());
        PRIMITIVE_TYPE_MAP.put(Long.class.getName(), long.class.getName());
        PRIMITIVE_TYPE_MAP.put(Float.class.getName(), float.class.getName());
        PRIMITIVE_TYPE_MAP.put(Double.class.getName(), double.class.getName());
        PRIMITIVE_TYPE_MAP.put(Void.class.getName(), void.class.getName());
    }

    private final String javaFileContent;

    public TemplateProcessor(String javaFileContent) {
        this.javaFileContent = javaFileContent;
    }

    public TemplateProcessor() {
        this(null);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getRootElements()) {
            JCCompilationUnit jcCompilationUnit = toUnit(element);
            if (jcCompilationUnit != null) {
                maybeGenerateTemplateSources(jcCompilationUnit);
            }
        }

        return true;
    }

    void maybeGenerateTemplateSources(JCCompilationUnit cu) {
        Context context = javacProcessingEnv.getContext();
        JavacResolution res = new JavacResolution(context);

        new TreeScanner() {
            @Override
            public void visitApply(JCTree.JCMethodInvocation tree) {
                JCTree.JCExpression jcSelect = tree.getMethodSelect();
                String name = jcSelect instanceof JCTree.JCFieldAccess ?
                        ((JCTree.JCFieldAccess) jcSelect).name.toString() :
                        ((JCTree.JCIdent) jcSelect).getName().toString();

                if (("expression".equals(name) || "statement".equals(name)) && tree.getArguments().size() == 3) {
                    JCTree.JCMethodInvocation resolvedMethod;
                    Map<JCTree, JCTree> resolved;
                    try {
                        resolved = res.resolveAll(context, cu, singletonList(tree));
                        resolvedMethod = (JCTree.JCMethodInvocation) resolved.get(tree);
                    } catch (Throwable t) {
                        processingEnv.getMessager().printMessage(Kind.WARNING, "Had trouble type attributing the template.");
                        return;
                    }

                    JCTree.JCExpression arg2 = tree.getArguments().get(2);
                    if (isOfClassType(resolvedMethod.type, "org.openrewrite.java.JavaTemplate.Builder") &&
                        (arg2 instanceof JCTree.JCLambda || arg2 instanceof JCTree.JCTypeCast && ((JCTree.JCTypeCast) arg2).getExpression() instanceof JCTree.JCLambda)) {

                        JCTree.JCLambda template = arg2 instanceof JCTree.JCLambda ? (JCTree.JCLambda) arg2 : (JCTree.JCLambda) ((JCTree.JCTypeCast) arg2).getExpression();

                        NavigableMap<Integer, JCTree.JCVariableDecl> parameterPositions;
                        List<JCTree.JCVariableDecl> parameters;
                        if (template.getParameters().isEmpty()) {
                            parameterPositions = emptyNavigableMap();
                            parameters = emptyList();
                        } else {
                            parameterPositions = new TreeMap<>();
                            Map<JCTree, JCTree> parameterResolution = res.resolveAll(context, cu, template.getParameters());
                            parameters = new ArrayList<>(template.getParameters().size());
                            for (VariableTree p : template.getParameters()) {
                                parameters.add((JCTree.JCVariableDecl) parameterResolution.get((JCTree) p));
                            }
                            JCTree.JCLambda resolvedTemplate = (JCTree.JCLambda) parameterResolution.get(template);

                            new TreeScanner() {
                                @Override
                                public void visitIdent(JCTree.JCIdent ident) {
                                    for (JCTree.JCVariableDecl parameter : parameters) {
                                        if (parameter.sym == ident.sym) {
                                            parameterPositions.put(ident.getStartPosition(), parameter);
                                        }
                                    }
                                }
                            }.scan(resolvedTemplate.getBody());
                        }

                        try (InputStream inputStream = javaFileContent == null ?
                                cu.getSourceFile().openInputStream() : new ByteArrayInputStream(javaFileContent.getBytes())) {
                            //noinspection ResultOfMethodCallIgnored
                            inputStream.skip(template.getBody().getStartPosition());

                            byte[] templateSourceBytes = new byte[template.getBody().getEndPosition(cu.endPositions) - template.getBody().getStartPosition()];

                            //noinspection ResultOfMethodCallIgnored
                            inputStream.read(templateSourceBytes);

                            String templateSource = new String(templateSourceBytes);
                            templateSource = templateSource.replace("\"", "\\\"");

                            for (Map.Entry<Integer, JCTree.JCVariableDecl> paramPos : parameterPositions.descendingMap().entrySet()) {
                                JCTree.JCVariableDecl param = paramPos.getValue();
                                String type = param.type.toString();
                                for (JCTree.JCAnnotation annotation : param.getModifiers().getAnnotations()) {
                                    if (annotation.type.tsym.getQualifiedName().contentEquals(PRIMITIVE_ANNOTATION)) {
                                        type = PRIMITIVE_TYPE_MAP.get(param.type.toString());
                                        // don't generate the annotation into the source code
                                        param.mods.annotations = com.sun.tools.javac.util.List.filter(param.mods.annotations, annotation);
                                    }
                                }
                                templateSource = templateSource.substring(0, paramPos.getKey() - template.getBody().getStartPosition()) +
                                                 "#{any(" + type + ")}" +
                                                 templateSource.substring((paramPos.getKey() - template.getBody().getStartPosition()) +
                                                                          param.name.length());
                            }

                            JCTree.JCLiteral templateName = (JCTree.JCLiteral) tree.getArguments().get(1);
                            if (templateName.value == null) {
                                processingEnv.getMessager().printMessage(Kind.WARNING, "Can't compile a template with a null name.");
                                return;
                            }

                            // this could be a visitor in the case that the visitor is in its own file or
                            // named inner class, or a recipe if the visitor is defined in an anonymous class
                            JCTree.JCClassDecl classDecl = cursor(cu, template).stream()
                                    .filter(JCTree.JCClassDecl.class::isInstance)
                                    .map(JCTree.JCClassDecl.class::cast)
                                    .reduce((next, acc) -> next)
                                    .orElseThrow(() -> new IllegalStateException("Expected to find an enclosing class"));

                            String templateFqn;

                            if (isOfClassType(classDecl.type, "org.openrewrite.java.JavaVisitor")) {
                                templateFqn = classDecl.sym.fullname.toString() + "_" + templateName.getValue().toString();
                            } else {
                                JCTree.JCNewClass visitorClass = cursor(cu, template).stream()
                                        .filter(JCTree.JCNewClass.class::isInstance)
                                        .map(JCTree.JCNewClass.class::cast)
                                        .reduce((next, acc) -> next)
                                        .orElse(null);

                                JCTree.JCNewClass resolvedVisitorClass = (JCTree.JCNewClass) resolved.get(visitorClass);

                                if (resolvedVisitorClass != null && isOfClassType(resolvedVisitorClass.clazz.type, "org.openrewrite.java.JavaVisitor")) {
                                    templateFqn = ((Symbol.ClassSymbol) resolvedVisitorClass.type.tsym).flatname.toString() + "_" +
                                                  templateName.getValue().toString();
                                } else {
                                    processingEnv.getMessager().printMessage(Kind.WARNING, "Can't compile a template outside of a visitor or recipe.");
                                    return;
                                }
                            }

                            JavaFileObject builderFile = processingEnv.getFiler().createSourceFile(templateFqn);
                            try (Writer out = new BufferedWriter(builderFile.openWriter())) {
                                out.write("package " + classDecl.sym.packge().toString() + ";\n");
                                out.write("import org.openrewrite.java.*;\n");


                                for (JCTree.JCVariableDecl parameter : parameters) {
                                    if (parameter.type.tsym instanceof Symbol.ClassSymbol) {
                                        String paramType = parameter.type.tsym.getQualifiedName().toString();
                                        if (!paramType.startsWith("java.lang")) {
                                            out.write("import " + paramType + ";\n");
                                        }
                                    }
                                }

                                out.write("\n");
                                out.write("public class " + templateFqn.substring(templateFqn.lastIndexOf('.') + 1) + " {\n");
                                out.write("    public static JavaTemplate.Builder getTemplate(JavaVisitor<?> visitor) {\n");
                                out.write("        return JavaTemplate\n");
                                out.write("                .builder(\"" + templateSource + "\")");

                                List<Symbol> imports = ImportDetector.imports(resolved.get(template));
                                String classpath = ClasspathJarNameDetector.classpathFor(resolved.get(template), imports);
                                if (!classpath.isEmpty()) {
                                    out.write("\n                .javaParser(JavaParser.fromJavaVersion().classpath(" +
                                              classpath + "))");
                                }

                                for (Symbol anImport : imports) {
                                    if (anImport instanceof Symbol.ClassSymbol && !anImport.getQualifiedName().toString().startsWith("java.lang.")) {
                                        out.write("\n                .imports(\"" + ((Symbol.ClassSymbol) anImport).fullname.toString().replace('$', '.') + "\")");
                                    } else if (anImport instanceof Symbol.VarSymbol || anImport instanceof Symbol.MethodSymbol) {
                                        out.write("\n                .staticImports(\"" + anImport.owner.getQualifiedName().toString().replace('$', '.') + '.' + anImport.flatName().toString() + "\")");
                                    }
                                }

                                out.write(";\n");
                                out.write("    }\n");
                                out.write("}\n");
                                out.flush();
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }

                super.visitApply(tree);
            }
        }.scan(cu);
    }

    private boolean isOfClassType(Type type, String fqn) {
        return type instanceof Type.ClassType && (((Symbol.ClassSymbol) type.tsym)
                                                          .fullname.contentEquals(fqn) || isOfClassType(((Type.ClassType) type).supertype_field, fqn));
    }

    private Stack<Tree> cursor(JCCompilationUnit cu, Tree t) {
        AtomicReference<Stack<Tree>> matching = new AtomicReference<>();
        new TreePathScanner<Stack<Tree>, Stack<Tree>>() {
            @Override
            public Stack<Tree> scan(Tree tree, Stack<Tree> parent) {
                Stack<Tree> cursor = new Stack<>();
                cursor.addAll(parent);
                cursor.push(tree);
                if (tree == t) {
                    matching.set(cursor);
                    return cursor;
                }
                return super.scan(tree, cursor);
            }
        }.scan(cu, new Stack<>());
        return matching.get();
    }
}
