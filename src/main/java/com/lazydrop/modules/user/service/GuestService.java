package com.lazydrop.modules.user.service;

import com.lazydrop.modules.user.model.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GuestService {
    private static final String GUEST_COOKIE_NAME = "ld_guest_id";
    private static final int GUEST_COOKIE_MAX_AGE = 60 * 60 * 24 * 30; // 30 days

    @Value("${app.cookies.secure:false}")
    private boolean secureCookies;

    private final UserService userService;

    public User resolveOrCreateGuest(HttpServletRequest request,
                                     HttpServletResponse response) {
        String guestId = extractGuestIdFromCookie(request);

        if (guestId != null) {
            return userService.findByGuestId(guestId)
                    .orElseGet(() -> createAndSetGuest(response));
        }

        return createAndSetGuest(response);
    }

    public String extractGuestIdFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if (GUEST_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void clearGuestCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(GUEST_COOKIE_NAME, "")
                .path("/")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private User createAndSetGuest(HttpServletResponse response) {
        String guestId = UUID.randomUUID().toString();
        User guest = userService.createGuestUser(guestId);

        ResponseCookie cookie = ResponseCookie.from(GUEST_COOKIE_NAME, guestId)
                .path("/")
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .maxAge(GUEST_COOKIE_MAX_AGE)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return guest;
    }
}
