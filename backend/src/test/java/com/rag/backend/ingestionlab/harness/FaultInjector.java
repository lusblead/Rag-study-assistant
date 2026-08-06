// 精确注入崩溃，用断言证明可靠性不变量。
package com.rag.backend.ingestionlab.harness;

@FunctionalInterface
// 故障注入端口：在指定位置只模拟崩溃一次。
public interface FaultInjector {
    void hit(FailurePoint point, int itemIndex);

    static FaultInjector none() { return (point, index) -> { }; }

    static FaultInjector failOnce(FailurePoint target, int targetIndex) {
        return new FaultInjector() {
            private boolean fired;
            @Override public void hit(FailurePoint point, int index) {
                if (!fired && point == target
                        && (targetIndex < 0 || targetIndex == index)) {
                    fired = true;
                    throw new InjectedCrash(point, index);
                }
            }
        };
    }

    // InjectedCrash：稳定失败类型，上层不解析异常文案。
    final class InjectedCrash extends RuntimeException {
        // 异常携带故障点与元素序号，Harness 把它视为预期崩溃而非生产错误分类。
        public InjectedCrash(FailurePoint point, int index) {
            super("Injected crash at " + point + ", item=" + index);
        }
    }
}