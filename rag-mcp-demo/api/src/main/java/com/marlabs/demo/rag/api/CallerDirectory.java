package com.marlabs.demo.rag.api;

import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class CallerDirectory {

    private final Map<String, Caller> callers = Map.of(
            "atlas-employee-01", new Caller("atlas", "employee"),
            "atlas-contractor-01", new Caller("atlas", "contractor"),
            "boreal-employee-01", new Caller("boreal", "employee"));

    public Caller find(String callerId) {
        Caller caller = callers.get(callerId);
        if (caller == null) {
            throw new UnknownCallerException();
        }
        return caller;
    }

    public record Caller(String tenant, String role) {
    }

    public static class UnknownCallerException extends RuntimeException {
    }
}
