package com.portfolio.mdslab.settlement;

import com.portfolio.mdslab.domain.SettlementTargetType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * [사실 근거] "수정 API에 애너테이션 + AOP 인터셉터 적용해 정산 중 변경 요청
 * 사전 차단" (facts/projects/mds-global-distribution.md 1번 Decision).
 * design/mds-global-distribution.md 5.2절과 동일한 형태.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SettlementGuarded {

    SettlementTargetType type();

    /** SpEL로 메서드 파라미터에서 대상 ID를 추출할 때 쓸 파라미터 이름. */
    String idParam() default "id";
}
