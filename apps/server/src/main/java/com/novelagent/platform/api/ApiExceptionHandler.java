package com.novelagent.platform.api;

import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.planning.application.StoryDirectionSetNotFoundException;
import com.novelagent.planning.application.StoryBibleVersionNotFoundException;
import com.novelagent.planning.application.OutlineVersionNotFoundException;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import com.novelagent.planning.application.PlanningCheckpointException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(com.novelagent.agent.application.GenerationStoppedException.class)
    ProblemDetail handleGenerationStopped(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "GENERATION_CANCELLED", "生成已停止", exception.getMessage(), request);
    }

    @ExceptionHandler(com.novelagent.agent.application.GenerationStopConflictException.class)
    ProblemDetail handleStopConflict(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "GENERATION_STOP_CONFLICT", "当前任务不能停止", exception.getMessage(), request);
    }

    @ExceptionHandler(PlanningCheckpointException.class)
    ProblemDetail handlePlanningCheckpoint(PlanningCheckpointException exception, HttpServletRequest request) {
        return problem(exception.isMissing() ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                exception.isMissing() ? "RESOURCE_NOT_FOUND" : "PLANNING_CHECKPOINT_CONFLICT",
                "规划分块不可用", exception.getMessage(), request);
    }

    @ExceptionHandler({ProjectNotFoundException.class, StoryDirectionSetNotFoundException.class,
            StoryBibleVersionNotFoundException.class, OutlineVersionNotFoundException.class,
            WritingResourceNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "资源不存在",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(ResourceVersionConflictException.class)
    ProblemDetail handleVersionConflict(ResourceVersionConflictException exception, HttpServletRequest request) {
        ProblemDetail detail = problem(
                HttpStatus.CONFLICT,
                "RESOURCE_VERSION_CONFLICT",
                "资源版本冲突",
                exception.getMessage(),
                request);
        detail.setProperty("meta", Map.of(
                "expectedVersion", exception.getExpectedVersion(),
                "actualVersion", exception.getActualVersion()));
        return detail;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, MethodArgumentNotValidException.class})
    ProblemDetail handleBadRequest(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "请求内容不合法",
                exception.getMessage(),
                request);
    }

    @ExceptionHandler(ModelProviderException.class)
    ProblemDetail handleModelProvider(ModelProviderException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_GATEWAY,
                "MODEL_PROVIDER_ERROR",
                "模型服务暂时不可用",
                exception.getMessage(),
                request);
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String code,
            String title,
            String message,
            HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(URI.create("https://novel-agent.local/problems/" + code.toLowerCase().replace('_', '-')));
        detail.setTitle(title);
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("code", code);
        return detail;
    }
}
