package com.pnomeer.lab.app;

/** Removal notice for the legacy text-block screens, kept reachable with ?legacy=true for one release. */
public final class LegacyNotice {
    public static final String HTML = """
            <div role="note" data-legacy-notice style="margin:0;padding:10px 16px;background:#342e24;color:#f3cb84;\
            border-bottom:1px solid #e9b45c;font:14px/1.5 system-ui,sans-serif">\
            이 기존 화면은 다음 릴리스에서 제거됩니다. 같은 기능은 <a href="/app/runs" style="color:#f3cb84">새 대시보드</a>에서 사용하세요.\
            </div>""";
    private LegacyNotice() { }

    public static String inject(String html) {
        int body = html.indexOf("<body>");
        return body < 0 ? html : html.substring(0, body + 6) + "\n" + HTML + html.substring(body + 6);
    }
}
