package gov.rajasthan.smart.srse.decision;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Package 7 governance: officer edits fork via new scenarios; there must be no
 * HTTP route that overwrites an existing scenario ruleset. This passes today
 * because {@link DecisionController} exposes no scenario update/delete — keep
 * it that way unless the fork decision is explicitly revisited.
 */
class DecisionScenarioRoutesGuardTest {

    @Test
    void decisionScenariosExposeNoPutPatchOrDelete() {
        String classPrefix = classLevelPath(DecisionController.class);
        boolean forbidden = Arrays.stream(DecisionController.class.getDeclaredMethods())
                .filter(DecisionScenarioRoutesGuardTest::mapsUnderScenarios)
                .anyMatch(DecisionScenarioRoutesGuardTest::isMutatingUpdateOrDelete);
        assertFalse(forbidden,
                "DecisionController must not expose PUT/PATCH/DELETE under /api/decision/scenarios/**");
    }

    @Test
    void decisionScenariosStillAllowGetAndPostCreate() {
        boolean hasList = hasMapping(DecisionController.class, "GET", "/scenarios");
        boolean hasCreate = hasMapping(DecisionController.class, "POST", "/scenarios");
        boolean hasGetById = hasMapping(DecisionController.class, "GET", "/scenarios/{id}");
        assertTrue(hasList && hasCreate && hasGetById);
    }

    private static boolean mapsUnderScenarios(Method method) {
        for (var ann : method.getAnnotations()) {
            String path = pathFromMappingAnnotation(ann);
            if (path != null && path.contains("scenarios")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMutatingUpdateOrDelete(Method method) {
        return method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }

    private static boolean hasMapping(Class<?> controller, String httpMethod, String pathSuffix) {
        String prefix = classLevelPath(controller);
        return Arrays.stream(controller.getDeclaredMethods()).anyMatch(m -> {
            for (var ann : m.getAnnotations()) {
                String path = pathFromMappingAnnotation(ann);
                if (path == null) {
                    continue;
                }
                String full = normalizePath(prefix + path);
                if (!full.endsWith(normalizePath(pathSuffix))) {
                    continue;
                }
                String annName = ann.annotationType().getSimpleName();
                return switch (httpMethod) {
                    case "GET" -> annName.equals("GetMapping");
                    case "POST" -> annName.equals("PostMapping");
                    default -> false;
                };
            }
            return false;
        });
    }

    private static String classLevelPath(Class<?> controller) {
        RequestMapping rm = controller.getAnnotation(RequestMapping.class);
        if (rm == null || rm.value().length == 0) {
            return "";
        }
        return rm.value()[0];
    }

    private static String pathFromMappingAnnotation(java.lang.annotation.Annotation ann) {
        if (ann instanceof org.springframework.web.bind.annotation.GetMapping g) {
            return g.value().length > 0 ? g.value()[0] : "";
        }
        if (ann instanceof org.springframework.web.bind.annotation.PostMapping p) {
            return p.value().length > 0 ? p.value()[0] : "";
        }
        if (ann instanceof org.springframework.web.bind.annotation.PutMapping p) {
            return p.value().length > 0 ? p.value()[0] : "";
        }
        if (ann instanceof org.springframework.web.bind.annotation.DeleteMapping d) {
            return d.value().length > 0 ? d.value()[0] : "";
        }
        if (ann instanceof org.springframework.web.bind.annotation.PatchMapping p) {
            return p.value().length > 0 ? p.value()[0] : "";
        }
        return null;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
