package com.maslul.app

import com.maslul.app.data.GoogleRoutesApi
import com.maslul.app.data.GoogleRoutesApi.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleRoutesTest {
    private fun refusal(status: String, reason: String?, message: String = "") = """
        [{"error": {"code": 403, "message": "$message", "status": "$status",
          "details": [${reason?.let { """{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "$it", "domain": "googleapis.com"}""" } ?: ""}]}}]
    """

    @Test
    fun saysWhyGoogleRefusesTheKey() {
        // As Google answered a real key without billing (the matrix endpoint wraps errors in a list).
        assertEquals(Problem.BILLING, GoogleRoutesApi.problemOf(403, refusal("PERMISSION_DENIED", "BILLING_DISABLED", "This API method requires billing to be enabled.")))
        assertEquals(Problem.API_DISABLED, GoogleRoutesApi.problemOf(403, refusal("PERMISSION_DENIED", "SERVICE_DISABLED")))
        assertEquals(Problem.INVALID_KEY, GoogleRoutesApi.problemOf(400, refusal("INVALID_ARGUMENT", "API_KEY_INVALID")))
        assertEquals(Problem.RESTRICTED, GoogleRoutesApi.problemOf(403, refusal("PERMISSION_DENIED", "API_KEY_ANDROID_APP_BLOCKED")))
        assertEquals(Problem.RESTRICTED, GoogleRoutesApi.problemOf(403, refusal("PERMISSION_DENIED", "API_KEY_SERVICE_BLOCKED")))
        assertEquals(Problem.QUOTA, GoogleRoutesApi.problemOf(429, refusal("RESOURCE_EXHAUSTED", null)))
        // Not a list, and no reason given.
        assertEquals(Problem.INVALID_KEY, GoogleRoutesApi.problemOf(400, """{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT"}}"""))
        assertEquals(Problem.OTHER, GoogleRoutesApi.problemOf(500, "<html>oops</html>"))
    }
}
