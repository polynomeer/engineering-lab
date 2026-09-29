package com.portfolio.mdslab.settlement;

import com.portfolio.mdslab.domain.SettlementLock;
import com.portfolio.mdslab.domain.SettlementTargetType;
import com.portfolio.mdslab.repository.SettlementLockRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * [사실 근거] "수정 API에 애너테이션 + AOP 인터셉터 적용해 정산 중 변경 요청
 * 사전 차단. 물리적 DB 락 대신 상태 기반 제어" (facts 1번 Decision).
 *
 * 락 획득/해제는 이 Aspect의 책임이 아니다 — {@link SettlementLockRepository#tryAcquire}/
 * {@code release}를 통해 정산 배치가 수행하고, 이 Aspect는 "현재 상태를 읽기만
 * 하는" 가드 역할만 한다(design 5.2절 "락 획득/해제는 정산 배치가 수행하며,
 * 가드는 읽기 전용 체크만 한다"). 이 분리 덕분에 가드 쪽에는 쓰기 경합이 전혀
 * 없다 — 읽기 전용 조회이므로 여러 요청이 동시에 가드를 통과 시도해도 서로를
 * 블로킹하지 않는다.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class SettlementLockAspect {

    private final SettlementLockRepository lockRepository;
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();
    private final ExpressionParser parser = new SpelExpressionParser();

    @Before("@annotation(guarded)")
    public void checkNotSettling(JoinPoint joinPoint, SettlementGuarded guarded) {
        Long targetId = extractTargetId(joinPoint, guarded.idParam());
        SettlementTargetType type = guarded.type();

        lockRepository.findByTargetTypeAndTargetId(type, targetId)
                .filter(lock -> lock.isActiveConsidering(LocalDateTime.now()))
                .ifPresent(lock -> {
                    throw new SettlementInProgressException(type, targetId, lock.getExpiresAt());
                });
    }

    private Long extractTargetId(JoinPoint joinPoint, String idParam) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = nameDiscoverer.getParameterNames(signature.getMethod());
        if (names == null) {
            throw new IllegalStateException(
                    "파라미터 이름을 알아낼 수 없습니다 — -parameters 컴파일 옵션이 꺼져 있는지 확인하세요.");
        }
        EvaluationContext context = new StandardEvaluationContext();
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < names.length; i++) {
            context.setVariable(names[i], args[i]);
        }
        return parser.parseExpression("#" + idParam).getValue(context, Long.class);
    }
}
