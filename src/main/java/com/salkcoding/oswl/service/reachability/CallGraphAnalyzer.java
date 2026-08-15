package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.enums.Reachability;
import lombok.extern.slf4j.Slf4j;
import org.objectweb.asm.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.stream.Stream;

/**
 * Builds a class reference graph from project bytecode and decides whether a
 * vulnerable library package/class is reachable.
 *
 * <p>The analyzer walks every {@code .class} file under a given root (directory or
 * JAR). It records, for each class, every internal class name it references through
 * inheritance, fields, methods, annotations, and method instructions. A library is
 * considered reachable if any class in the project bytecode references at least one
 * class whose internal name starts with one of the supplied library prefixes.</p>
 */
@Slf4j
@Component
public class CallGraphAnalyzer {

    /** How many project-class -> library-class references to keep as displayable evidence. */
    private static final int MAX_EVIDENCE = 5;

    /** One concrete reference the analyzer found: our class X calls/extends/uses library class Y. */
    public record ReferenceEvidence(String referencingClass, String referencedClass) {}

    /** {@link Reachability} plus the evidence backing a REACHABLE verdict (empty otherwise). */
    public record AnalysisResult(Reachability reachability, List<ReferenceEvidence> evidence) {
        static AnalysisResult of(Reachability reachability) {
            return new AnalysisResult(reachability, List.of());
        }
    }

    /**
     * Analyzes the bytecode under {@code projectBytecodeRoot} for references to any
     * class prefix in {@code libraryClassPrefixes}.
     *
     * @param projectBytecodeRoot directory or JAR containing project class files;
     *                            may be null or non-existent
     * @param libraryClassPrefixes internal class-name prefixes (e.g. "org/springframework/core/")
     * @return REACHABLE (with up to {@value #MAX_EVIDENCE} evidence entries) if a reference is
     *         found, NOT_REACHABLE if the root was read but no reference was found, UNKNOWN if
     *         the root is missing/unreadable
     */
    public AnalysisResult analyze(Path projectBytecodeRoot, Set<String> libraryClassPrefixes) {
        if (projectBytecodeRoot == null || !Files.exists(projectBytecodeRoot)) {
            return AnalysisResult.of(Reachability.UNKNOWN);
        }
        if (libraryClassPrefixes == null || libraryClassPrefixes.isEmpty()) {
            return AnalysisResult.of(Reachability.UNKNOWN);
        }

        List<ReferenceEvidence> evidence = new ArrayList<>();
        boolean[] anyClassRead = {false};
        try {
            if (Files.isDirectory(projectBytecodeRoot)) {
                walkDirectory(projectBytecodeRoot, libraryClassPrefixes, evidence, anyClassRead);
            } else {
                walkJar(projectBytecodeRoot, libraryClassPrefixes, evidence, anyClassRead);
            }
        } catch (IOException e) {
            log.warn("[Reachability] Failed to read bytecode root {}: {}",
                    projectBytecodeRoot, e.getMessage());
            return AnalysisResult.of(Reachability.UNKNOWN);
        }

        if (!anyClassRead[0]) {
            return AnalysisResult.of(Reachability.UNKNOWN);
        }
        if (evidence.isEmpty()) {
            return AnalysisResult.of(Reachability.NOT_REACHABLE);
        }
        log.debug("[Reachability] Found {} reference(s), e.g. {}", evidence.size(), evidence.getFirst());
        return new AnalysisResult(Reachability.REACHABLE, evidence);
    }

    private void walkDirectory(Path root, Set<String> libraryClassPrefixes,
                               List<ReferenceEvidence> evidence, boolean[] anyClassRead) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.toList()) {
                if (evidence.size() >= MAX_EVIDENCE) break;
                if (Files.isRegularFile(path) && path.toString().endsWith(".class")) {
                    try (InputStream in = Files.newInputStream(path)) {
                        collectClassEvidence(in, libraryClassPrefixes, evidence);
                        anyClassRead[0] = true;
                    } catch (IOException e) {
                        log.debug("[Reachability] Skipping unreadable class file {}: {}",
                                path, e.getMessage());
                    }
                }
            }
        }
    }

    private void walkJar(Path jar, Set<String> libraryClassPrefixes,
                         List<ReferenceEvidence> evidence, boolean[] anyClassRead) throws IOException {
        try (InputStream fileIn = Files.newInputStream(jar);
             JarInputStream jarIn = new JarInputStream(fileIn)) {
            JarEntry entry;
            while ((entry = jarIn.getNextJarEntry()) != null) {
                if (evidence.size() >= MAX_EVIDENCE) break;
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    try {
                        collectClassEvidence(jarIn, libraryClassPrefixes, evidence);
                        anyClassRead[0] = true;
                    } catch (IOException e) {
                        log.debug("[Reachability] Skipping unreadable jar entry {}: {}",
                                entry.getName(), e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Parses one class file, collects everything it references, and — if any reference matches
     * a library prefix — records which of the project's own classes did the referencing. Unlike
     * the merged-set approach this replaced, this is what actually lets a REACHABLE verdict point
     * at a specific "class X in your code uses class Y in the vulnerable library" fact instead of
     * just asserting a boolean.
     */
    private void collectClassEvidence(InputStream in, Set<String> libraryClassPrefixes,
                                      List<ReferenceEvidence> evidence) throws IOException {
        ClassReader reader = new ClassReader(in);
        String ownClass = reader.getClassName();
        Set<String> referenced = new LinkedHashSet<>();
        ReferenceVisitor visitor = new ReferenceVisitor(referenced);
        reader.accept(visitor, ClassReader.SKIP_FRAMES);

        for (String ref : referenced) {
            if (evidence.size() >= MAX_EVIDENCE) break;
            for (String prefix : libraryClassPrefixes) {
                if (ref.startsWith(prefix)) {
                    evidence.add(new ReferenceEvidence(toDisplayName(ownClass), toDisplayName(ref)));
                    break;
                }
            }
        }
    }

    private static String toDisplayName(String internalName) {
        return internalName == null ? null : internalName.replace('/', '.');
    }

    /**
     * ASM visitor that records every internal class name referenced by a class.
     */
    private static class ReferenceVisitor extends ClassVisitor {

        private final Set<String> referenced;

        ReferenceVisitor(Set<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            add(superName);
            if (interfaces != null) {
                for (String iface : interfaces) {
                    add(iface);
                }
            }
            if (signature != null) {
                addSignatureTypes(signature);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return new AnnotationReferenceVisitor(referenced);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            addDescriptor(descriptor);
            if (signature != null) {
                addSignatureTypes(signature);
            }
            return new FieldReferenceVisitor(referenced);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            addDescriptor(descriptor);
            if (exceptions != null) {
                for (String ex : exceptions) {
                    add(ex);
                }
            }
            if (signature != null) {
                addSignatureTypes(signature);
            }
            return new MethodReferenceVisitor(referenced);
        }

        private void add(String internalName) {
            if (internalName != null && !internalName.startsWith("java/")
                    && !internalName.startsWith("javax/")
                    && !internalName.startsWith("jdk/")
                    && !internalName.startsWith("sun/")
                    && !internalName.startsWith("com/sun/")) {
                referenced.add(internalName);
            }
        }

        private void addDescriptor(String descriptor) {
            if (descriptor == null) return;
            Type type = Type.getType(descriptor);
            addType(type);
        }

        private void addType(Type type) {
            if (type == null) return;
            switch (type.getSort()) {
                case Type.OBJECT -> add(type.getInternalName());
                case Type.ARRAY -> addType(type.getElementType());
                case Type.METHOD -> {
                    addType(type.getReturnType());
                    for (Type arg : type.getArgumentTypes()) {
                        addType(arg);
                    }
                }
            }
        }

        private void addSignatureTypes(String signature) {
            if (signature == null) return;
            // Best-effort extraction of class names from generic signatures.
            // L<class>; is the JVM descriptor form used inside signatures.
            int i = 0;
            while ((i = signature.indexOf('L', i)) != -1) {
                int end = signature.indexOf(';', i);
                if (end == -1) break;
                String internal = signature.substring(i + 1, end);
                // Skip generic type variables (<T>) and primitive arrays
                if (!internal.startsWith("<") && !internal.isEmpty()) {
                    add(internal);
                }
                i = end + 1;
            }
        }
    }

    private static class MethodReferenceVisitor extends MethodVisitor {

        private final Set<String> referenced;

        MethodReferenceVisitor(Set<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            add(type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            add(owner);
            addDescriptor(descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            add(owner);
            addDescriptor(descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            addDescriptor(descriptor);
            if (bootstrapMethodHandle != null) {
                add(bootstrapMethodHandle.getOwner());
                addDescriptor(bootstrapMethodHandle.getDesc());
            }
            for (Object arg : bootstrapMethodArguments) {
                if (arg instanceof Handle h) {
                    add(h.getOwner());
                    addDescriptor(h.getDesc());
                } else if (arg instanceof Type t) {
                    addType(t);
                }
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type t) {
                addType(t);
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            addDescriptor(descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return new AnnotationReferenceVisitor(referenced);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return new AnnotationReferenceVisitor(referenced);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                           boolean visible) {
            addDescriptor(descriptor);
            return new AnnotationReferenceVisitor(referenced);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            addDescriptor(descriptor);
            if (signature != null) {
                addSignatureTypes(signature);
            }
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            add(type);
        }

        private void add(String internalName) {
            CallGraphAnalyzer.addTo(referenced, internalName);
        }

        private void addDescriptor(String descriptor) {
            CallGraphAnalyzer.addDescriptorTo(referenced, descriptor);
        }

        private void addType(Type type) {
            CallGraphAnalyzer.addTypeTo(referenced, type);
        }

        private void addSignatureTypes(String signature) {
            CallGraphAnalyzer.addSignatureTypesTo(referenced, signature);
        }
    }

    private static class FieldReferenceVisitor extends FieldVisitor {

        private final Set<String> referenced;

        FieldReferenceVisitor(Set<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            CallGraphAnalyzer.addDescriptorTo(referenced, descriptor);
            return new AnnotationReferenceVisitor(referenced);
        }
    }

    private static class AnnotationReferenceVisitor extends AnnotationVisitor {

        private final Set<String> referenced;

        AnnotationReferenceVisitor(Set<String> referenced) {
            super(Opcodes.ASM9);
            this.referenced = referenced;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof Type t) {
                CallGraphAnalyzer.addTypeTo(referenced, t);
            }
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            CallGraphAnalyzer.addDescriptorTo(referenced, descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            CallGraphAnalyzer.addDescriptorTo(referenced, descriptor);
            return this;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return this;
        }
    }

    // Helper methods shared across nested visitors.
    private static void addTo(Set<String> referenced, String internalName) {
        if (internalName != null && !internalName.startsWith("java/")
                && !internalName.startsWith("javax/")
                && !internalName.startsWith("jdk/")
                && !internalName.startsWith("sun/")
                && !internalName.startsWith("com/sun/")) {
            referenced.add(internalName);
        }
    }

    private static void addDescriptorTo(Set<String> referenced, String descriptor) {
        if (descriptor == null) return;
        addTypeTo(referenced, Type.getType(descriptor));
    }

    private static void addTypeTo(Set<String> referenced, Type type) {
        if (type == null) return;
        switch (type.getSort()) {
            case Type.OBJECT -> addTo(referenced, type.getInternalName());
            case Type.ARRAY -> addTypeTo(referenced, type.getElementType());
            case Type.METHOD -> {
                addTypeTo(referenced, type.getReturnType());
                for (Type arg : type.getArgumentTypes()) {
                    addTypeTo(referenced, arg);
                }
            }
        }
    }

    private static void addSignatureTypesTo(Set<String> referenced, String signature) {
        if (signature == null) return;
        int i = 0;
        while ((i = signature.indexOf('L', i)) != -1) {
            int end = signature.indexOf(';', i);
            if (end == -1) break;
            String internal = signature.substring(i + 1, end);
            if (!internal.startsWith("<") && !internal.isEmpty()) {
                addTo(referenced, internal);
            }
            i = end + 1;
        }
    }
}
