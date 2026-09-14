package com.sdncustom.server.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把带远端/推送副作用的动作挪到事务提交之后再执行。
 *
 * <p>在 {@code @Transactional} 方法里直接调远端（订阅、连接、Redis、WS 推送）有两个问题：
 * 一是整段时间都占着 DB 连接做网络 I/O，二是事务回滚时远端已经动过了。
 * 注册成 afterCommit 后：提交成功才执行、回滚自动跳过。
 *
 * <p>没有活动事务时立即执行——单元测试、采集线程这类无事务场景行为不变。
 */
@Slf4j
public final class TransactionHooks {

    private TransactionHooks() {
    }

    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (Exception e) {
                    // 事务已提交，这里再抛也回滚不了，别把异常漏给调用方
                    log.error("Post-commit action failed", e);
                }
            }
        });
    }
}
