package com.novelagent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.writing.application.ChapterContractService;
import com.novelagent.writing.application.ChapterReviewService;
import com.novelagent.writing.application.ManuscriptService;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.application.WritingService;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

class ArchitectureTest {
    private static Map<String, Set<String>> dependencies;

    @BeforeAll static void scanCompiledApplication() throws Exception {
        Path root = Path.of(WritingService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        dependencies = new LinkedHashMap<>();
        try (var files = Files.walk(root.resolve("com/novelagent"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                var reader = new ClassReader(Files.readAllBytes(file));
                var references = new HashSet<String>();
                reader.accept(new DependencyVisitor(references), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                String name = reader.getClassName().replace('/', '.');
                references.remove(name);
                dependencies.put(name, Set.copyOf(references));
            }
        }
        assertThat(dependencies).isNotEmpty();
    }

    @Test void controllersDoNotAccessPersistenceDirectly() {
        assertThat(dependencies.keySet().stream().filter(name -> name.endsWith("Controller")).toList()).isNotEmpty();
        dependencies.forEach((name, references) -> {
            if (!name.endsWith("Controller")) return;
            assertThat(references).as(name).noneMatch(type -> type.contains(".infrastructure.")
                    || type.startsWith("org.springframework.jdbc.") || type.startsWith("jakarta.persistence."));
        });
    }

    @Test void domainDoesNotDependOnTransportOrPersistenceAdapters() {
        dependencies.forEach((name, references) -> {
            if (!name.contains(".domain.")) return;
            assertThat(references).as(name).noneMatch(type -> type.startsWith("com.novelagent.")
                    && (type.contains(".api.") || type.contains(".infrastructure.")));
        });
    }

    @Test void writingFacadeOnlyCoordinatesThreeUseCaseServices() {
        assertThat(Arrays.stream(WritingService.class.getDeclaredFields()).map(field -> field.getType().getName()).toList())
                .containsExactlyInAnyOrder(ChapterContractService.class.getName(), ManuscriptService.class.getName(), ChapterReviewService.class.getName());
        assertThat(AnnotatedElementUtils.findMergedAnnotation(WritingService.class, Transactional.class)).isNull();
        for (var method : WritingService.class.getDeclaredMethods()) {
            assertThat(AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class)).as(method.toString()).isNull();
        }
    }

    @Test void specializedWritingServicesDoNotCallBackIntoFacade() {
        dependencies.forEach((name, references) -> {
            if (name.startsWith("com.novelagent.writing.application.") && !name.equals(WritingService.class.getName())) {
                assertThat(references).as(name).doesNotContain(WritingService.class.getName());
            }
        });
    }

    @Test void modelAdaptersUseTheSharedStructuredModelGateway() {
        Set<String> adapters = Set.of(
                "com.novelagent.planning.infrastructure.CodexAppServerOutlineGenerator",
                "com.novelagent.planning.infrastructure.CodexAppServerStoryBibleGenerator",
                "com.novelagent.planning.infrastructure.CodexAppServerStoryDirectionGenerator",
                "com.novelagent.planning.infrastructure.DeepSeekOutlineGenerator",
                "com.novelagent.planning.infrastructure.DeepSeekStoryBibleGenerator",
                "com.novelagent.planning.infrastructure.DeepSeekStoryDirectionGenerator",
                "com.novelagent.ingest.infrastructure.ImportedPlanningModelGateway",
                "com.novelagent.writing.infrastructure.WritingModelRouter");
        Set<String> infrastructureDetails = Set.of(
                "com.novelagent.agent.application.AgentRunRecorder",
                "com.novelagent.planning.infrastructure.CodexAgentSessionRepository",
                "com.novelagent.planning.infrastructure.CodexAppServerClient",
                "com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient");

        adapters.forEach(name -> {
            assertThat(dependencies).containsKey(name);
            assertThat(dependencies.get(name)).as(name).contains("com.novelagent.planning.infrastructure.StructuredModelGateway");
            assertThat(dependencies.get(name)).as(name).doesNotContainAnyElementsOf(infrastructureDetails);
            assertThat(dependencies.get(name)).as(name).noneMatch(type -> infrastructureDetails.stream()
                    .anyMatch(detail -> type.startsWith(detail + "$")));
        });
    }

    @Test void writingCollaboratorsRemainAcyclic() {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        dependencies.forEach((name, references) -> {
            if (!name.startsWith("com.novelagent.writing.application.")) return;
            String owner = topLevelClass(name);
            var edges = graph.computeIfAbsent(owner, ignored -> new HashSet<>());
            references.stream().map(ArchitectureTest::topLevelClass)
                    .filter(type -> type.startsWith("com.novelagent.writing.application.") && !type.equals(owner))
                    .forEach(edges::add);
        });
        for (String name : graph.keySet()) detectCycle(name, graph, new HashSet<>(), new HashSet<>());
    }

    private static String topLevelClass(String name) {
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private void detectCycle(String name, Map<String, Set<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(name)) return;
        assertThat(visiting.add(name)).as("Writing dependency cycle at %s: %s", name, visiting).isTrue();
        for (String reference : graph.getOrDefault(name, Set.of())) {
            if (graph.containsKey(reference)) detectCycle(reference, graph, visiting, visited);
        }
        visiting.remove(name);
        visited.add(name);
    }

    @Test void generationEntryPointsDoNotOpenLongDatabaseTransactions() {
        for (Class<?> type : List.of(ChapterContractService.class, ManuscriptService.class,
                ChapterReviewService.class, QualityReviewService.class,
                com.novelagent.writing.application.WritingStylePreviewService.class,
                com.novelagent.writing.application.WritingStyleRecommendationService.class)) {
            assertThat(AnnotatedElementUtils.findMergedAnnotation(type, Transactional.class)).as(type.getName()).isNull();
            for (var method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (name.startsWith("generate") || name.startsWith("prepare")
                        || name.equals("returnReviewToWriting") || (type == QualityReviewService.class && name.equals("revise"))) {
                    assertThat(AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class)).as(method.toString()).isNull();
                }
            }
        }
    }

    @Test void authorMutationEntryPointsKeepWriteTransactions() {
        for (Class<?> type : List.of(ChapterContractService.class, ManuscriptService.class, ChapterReviewService.class)) {
            for (var method : type.getDeclaredMethods()) {
                String name = method.getName();
                if (!Modifier.isPublic(method.getModifiers()) || !(name.startsWith("update") || name.startsWith("approve")
                        || name.equals("acceptManuscript") || name.equals("createManuscriptRevision"))) continue;
                Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
                assertThat(tx).as(method.toString()).isNotNull();
                assertThat(tx.readOnly()).as(method.toString()).isFalse();
            }
        }
    }

    // Inspect bytecode so method-local persistence access is covered, not just injected fields.
    private static final class DependencyVisitor extends ClassVisitor {
        private final Set<String> references;
        DependencyVisitor(Set<String> references) { super(Opcodes.ASM9); this.references = references; }

        private void addName(String name) { if (name != null) references.add(name.replace('/', '.')); }
        private void addType(Type type) {
            if (type.getSort() == Type.OBJECT) addName(type.getInternalName());
            else if (type.getSort() == Type.ARRAY) addType(type.getElementType());
            else if (type.getSort() == Type.METHOD) {
                addType(type.getReturnType());
                for (Type argument : type.getArgumentTypes()) addType(argument);
            }
        }
        @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            addName(superName);
            if (interfaces != null) for (String type : interfaces) addName(type);
        }
        @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addType(Type.getType(descriptor));
            return null;
        }
        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            addType(Type.getMethodType(descriptor));
            if (exceptions != null) for (String type : exceptions) addName(type);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override public void visitTypeInsn(int opcode, String type) { addName(type); }
                @Override public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                    addName(owner); addType(Type.getType(descriptor));
                }
                @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                    addName(owner); addType(Type.getMethodType(descriptor));
                }
                @Override public void visitLdcInsn(Object value) { if (value instanceof Type type) addType(type); }
            };
        }
    }
}
