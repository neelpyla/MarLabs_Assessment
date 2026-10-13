package com.MLab.assessment.service;

import com.MLab.assessment.model.CallerContext;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class CallerDirectory {
    private static final Map<String, CallerContext> CALLERS = Map.of(
            "atlas-employee-01", new CallerContext("Atlas", "employee"),
            "atlas-contractor-01", new CallerContext("Atlas", "contractor"),
            "boreal-employee-01", new CallerContext("Boreal", "employee")
    );

    public CallerContext requireCaller(String callerId) {
        CallerContext context = CALLERS.get(callerId);
        if (context == null) {
            throw new InvalidRequestException("UNKNOWN_CALLER", "X-Caller-Id is missing or unknown.");
        }
        return context;
    }
}
