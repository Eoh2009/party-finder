package com.eoh2009.partyfinder.client;

/** A lookup error with a short, human-readable reason that we show in chat. */
public class LookupException extends RuntimeException {
	public LookupException(String reason) {
		super(reason);
	}
}
