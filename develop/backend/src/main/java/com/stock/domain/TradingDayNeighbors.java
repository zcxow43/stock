package com.stock.domain;

import java.time.LocalDate;

/** Nearest market-wide trading days around a date: {@code previous <= date <= next}; either may be null. */
public class TradingDayNeighbors {
    private LocalDate previous;
    private LocalDate next;

    public LocalDate getPrevious() { return previous; }
    public void setPrevious(LocalDate previous) { this.previous = previous; }
    public LocalDate getNext() { return next; }
    public void setNext(LocalDate next) { this.next = next; }
}
