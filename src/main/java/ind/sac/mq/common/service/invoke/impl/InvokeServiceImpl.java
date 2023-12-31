package ind.sac.mq.common.service.invoke.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import ind.sac.mq.common.exception.MQException;
import ind.sac.mq.common.response.CommonResponseCode;
import ind.sac.mq.common.rpc.RPCMessage;
import ind.sac.mq.common.service.invoke.InvokeService;
import ind.sac.mq.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class InvokeServiceImpl implements InvokeService {

    private static final Logger logger = LoggerFactory.getLogger(InvokeServiceImpl.class);

    /**
     * key: sequence id
     * value: request valid alive time
     */
    private final ConcurrentHashMap<Long, Long> requestMap;

    private final ConcurrentHashMap<Long, RPCMessage> responseMap;

    public InvokeServiceImpl() {
        requestMap = new ConcurrentHashMap<>();
        responseMap = new ConcurrentHashMap<>();
        final Runnable timeoutThread = new TimeoutCheckThread(requestMap, responseMap);
        Executors.newScheduledThreadPool(1)
                .scheduleAtFixedRate(timeoutThread, 60, 60, TimeUnit.SECONDS);
    }

    @Override
    public void addRequest(long sequenceId, long timeout) {
        logger.debug("[Invoke] start add request for sequence ID: {}, timeout milliseconds: {}", sequenceId, timeout);
        final long expireTime = System.currentTimeMillis() + timeout;
        requestMap.putIfAbsent(sequenceId, expireTime);
    }

    /**
     * 功能包含：响应超时检查、请求的本地移除和响应本地记录
     *
     * @param sequenceId  trace ID
     * @param rpcResponse RPC响应体
     * @throws JsonProcessingException 逆序列化异常
     */
    @Override
    public void addResponse(long sequenceId, RPCMessage rpcResponse) throws JsonProcessingException {
        Long expireTime = this.requestMap.get(sequenceId);
        // 如果为空，可能是错误的请求id，也可能是已超时请求，被定时任务移除之后，服务端才开始处理请求
        // 直接忽略即可
        if (Objects.isNull(expireTime)) {
            return;
        }
        // 尽管配置了请求超时的定时检查任务，但可能定时间隔比过期时长大
        // 所以可能出现接收到响应已超时，但未到下一次请求超时检查任务执行时，这时请求没有被移除，所以在此仍需校验响应是否超时
        if (System.currentTimeMillis() > expireTime) {
            logger.debug("[Invoke] sequence ID: {} - request timeout.", sequenceId);
            rpcResponse = RPCMessage.timeout();
        }
        responseMap.putIfAbsent(sequenceId, rpcResponse);
        logger.debug("[Invoke] sequence ID: {} - RPC response: {}", sequenceId, JsonUtil.writeAsJsonString(rpcResponse));
        requestMap.remove(sequenceId);
        logger.debug("[Invoke] sequence ID: {} - Removed from request map.", sequenceId);
        // 同步块来获取当前对象的锁，并调用 notifyAll() 方法唤醒所有等待该对象锁的线程
        synchronized (this) {
            this.notifyAll();
            logger.debug("[Invoke] sequence ID: {} - Message put to map, notified all.", sequenceId);
        }
    }

    /**
     * 当response map中没有id键值对时进入wait，等待下一次向响应map中add响应时（不一定是本请求的）被notifyAll唤醒重试 <p>
     * 有可能请求已超时很久之后才迎来下一次其他请求对应的addResponse <p>
     * 在此之前该方法在无效wait，故应在命中率高时使用此方法，或减少超时响应 <p>
     * 尽管上一级调用者可能直接对Timeout抛出Exception，但这取决于上级
     *
     * @param traceId 请求的id，响应也用的是同一个id来追踪
     * @return rpcResponse
     */
    @Override
    public RPCMessage getResponse(long traceId) {
        RPCMessage rpcResponse = this.responseMap.get(traceId);
        if (Objects.nonNull(rpcResponse)) {
            logger.debug("[Invoke] trace ID:{} - Got RPC response: {}", traceId, rpcResponse);
            return rpcResponse;
        }
        while (Objects.isNull(rpcResponse)) {
            synchronized (this) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    throw new MQException(CommonResponseCode.FAIL);
                }
            }
            rpcResponse = this.responseMap.get(traceId);
        }
        return rpcResponse;
    }

    /**
     * 查看是否仍包含待处理请求
     *
     * @return 布尔结果
     */
    @Override
    public boolean remainsRequest() {
        return this.requestMap.size() > 0;
    }
}