package com.marlabs.demo.rag.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/answer")
public class AnswerController {

    private final CallerDirectory callerDirectory;
    private final PolicyAnswerService answerService;

    public AnswerController(CallerDirectory callerDirectory, PolicyAnswerService answerService) {
        this.callerDirectory = callerDirectory;
        this.answerService = answerService;
    }

    @PostMapping
    public AnswerResponse answer(
            @RequestHeader("X-Caller-Id") String callerId,
            @Valid @RequestBody AnswerRequest request) {
        return answerService.answer(callerDirectory.find(callerId), request);
    }

    @ExceptionHandler(CallerDirectory.UnknownCallerException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unknownCaller() {
        return new ErrorResponse("UNKNOWN_CALLER", "The supplied caller ID is not recognized.");
    }

    public record ErrorResponse(String code, String message) {
    }
}
