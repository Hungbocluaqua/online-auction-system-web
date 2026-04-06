package com.auction.web.dto;

public class CsrfTokenResponse {
    public final String csrfToken;
    public CsrfTokenResponse(String csrfToken) { this.csrfToken = csrfToken; }
}
