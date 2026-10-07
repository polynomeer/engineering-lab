package com.pnomeer.lab.app.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DashboardRoutingTest {
    @Test void redirectsOnlyViewGetsAndPreservesQuery() throws Exception {
        var filter = new DashboardRoutingFilter(true, "http://127.0.0.1:4173");
        var routes = Map.of("/experiments/ui", "/app/experiments", "/ingest/ui", "/app/experiments/pipeline.excel-ingestion",
            "/ingest/ui/db", "/app/tools/ingestion-db", "/lab/io/ui", "/app/tools/io-echo", "/ingest/ui/run-1", "/app/runs/run-1",
            "/experiments/ui/run-1", "/app/runs/run-1", "/runs/ui", "/app/runs");
        for (var e : routes.entrySet()) {
            var req = new MockHttpServletRequest("GET",e.getKey()); req.setParameter("msg","a & 한글");
            var res = new MockHttpServletResponse(); filter.doFilter(req,res,new MockFilterChain());
            assertEquals(302,res.getStatus()); assertTrue(res.getHeader("Location").startsWith("http://127.0.0.1:4173"+e.getValue()+"?"));
            assertTrue(res.getHeader("Location").contains("%26")); assertEquals("no-store",res.getHeader("Cache-Control"));
        }
        for (String method : new String[]{"POST","PUT","DELETE"}) {
            var res = new MockHttpServletResponse(); filter.doFilter(new MockHttpServletRequest(method,"/experiments/ui"),res,new MockFilterChain());
            assertNull(res.getHeader("Location"));
        }
        var req = new MockHttpServletRequest("GET","/runs/ui");req.setParameter("baseline","a");req.setParameter("candidate","b");
        var res = new MockHttpServletResponse();filter.doFilter(req,res,new MockFilterChain());
        assertTrue(res.getHeader("Location").contains("/app/compare?baseline=a&candidate=b"));
        req = new MockHttpServletRequest("GET","/runs/ui");req.setParameter("run","abc");req.setParameter("before","7");
        res = new MockHttpServletResponse();filter.doFilter(req,res,new MockFilterChain());
        assertTrue(res.getHeader("Location").endsWith("/app/runs/abc?before=7"));
    }
    @Test void keepsRollbackAndApiBoundaries() throws Exception {
        var req = new MockHttpServletRequest("GET","/experiments/ui");req.setParameter("legacy","true");
        var res = new MockHttpServletResponse();new DashboardRoutingFilter(true,"").doFilter(req,res,new MockFilterChain());assertNull(res.getHeader("Location"));
        res = new MockHttpServletResponse();new DashboardRoutingFilter(false,"").doFilter(new MockHttpServletRequest("GET","/runs/ui"),res,new MockFilterChain());assertNull(res.getHeader("Location"));
        res = new MockHttpServletResponse();new DashboardRoutingFilter(true,"").doFilter(new MockHttpServletRequest("GET","/api/runs/ui/options"),res,new MockFilterChain());
        assertEquals("/runs/ui/options",res.getForwardedUrl());assertNull(res.getHeader("Location"));
        res = new MockHttpServletResponse();new DashboardRoutingFilter(true,"").doFilter(new MockHttpServletRequest("GET","/api/app/runs"),res,new MockFilterChain());assertEquals(404,res.getStatus());
        res = new MockHttpServletResponse();new DashboardRoutingFilter(true, "").doFilter(new MockHttpServletRequest("GET", "/api/runs/%2e%2e/ui"),res,new MockFilterChain());assertEquals(404,res.getStatus());
        assertThrows(IllegalArgumentException.class,()->new DashboardRoutingFilter(true,"https://example.com/path"));
    }
}
