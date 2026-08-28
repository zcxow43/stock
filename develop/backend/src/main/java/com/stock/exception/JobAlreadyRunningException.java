package com.stock.exception;

public class JobAlreadyRunningException extends RuntimeException {

    public JobAlreadyRunningException(String jobType) {
        super("Job already running: " + jobType);
    }
}
