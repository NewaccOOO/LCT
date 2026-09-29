package ru.lct.heatnet.api;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

// Глобальный, а не внутри контроллера: неподходящий Content-Type отсекается до выбора метода контроллера.
@Profile("!cli")
@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getReason(), List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType() {
        String message = "Тело запроса принимается только с Content-Type application/json, application/geo+json или application/octet-stream";
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new ApiError(message, List.of()));
    }
}
