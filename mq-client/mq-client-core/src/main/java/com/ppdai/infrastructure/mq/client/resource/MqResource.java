package com.ppdai.infrastructure.mq.client.resource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Timer;
import com.ppdai.infrastructure.mq.biz.common.thread.SoaThreadFactory;
import com.ppdai.infrastructure.mq.biz.common.trace.Tracer;
import com.ppdai.infrastructure.mq.biz.common.trace.spi.Transaction;
import com.ppdai.infrastructure.mq.biz.common.util.BrokerException;
import com.ppdai.infrastructure.mq.biz.common.util.HttpClient;
import com.ppdai.infrastructure.mq.biz.common.util.IHttpClient;
import com.ppdai.infrastructure.mq.biz.common.util.IPUtil;
import com.ppdai.infrastructure.mq.biz.common.util.JsonUtil;
import com.ppdai.infrastructure.mq.biz.common.util.Util;
import com.ppdai.infrastructure.mq.biz.dto.BaseResponse;
import com.ppdai.infrastructure.mq.biz.dto.MqConstanst;
import com.ppdai.infrastructure.mq.biz.dto.client.CatRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.CatResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.CommitOffsetRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.CommitOffsetResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.ConsumerDeRegisterRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.ConsumerDeRegisterResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.ConsumerGroupRegisterRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.ConsumerGroupRegisterResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.ConsumerRegisterRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.ConsumerRegisterResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.FailMsgPublishAndUpdateResultRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.FailMsgPublishAndUpdateResultResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.GetConsumerGroupRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.GetConsumerGroupResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.GetGroupTopicRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.GetGroupTopicResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.GetMessageCountRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.GetMessageCountResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.GetMetaGroupRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.GetMetaGroupResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.GetTopicQueueIdsRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.GetTopicQueueIdsResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.GetTopicRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.GetTopicResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.HeartbeatRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.HeartbeatResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.LogRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.LogResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.OpLogRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.OpLogResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.PublishMessageRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.PublishMessageResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.PullDataRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.PullDataResponse;
import com.ppdai.infrastructure.mq.biz.dto.client.SendMailRequest;
import com.ppdai.infrastructure.mq.biz.dto.client.SendMailResponse;
import com.ppdai.infrastructure.mq.client.metric.MetricSingleton;

public class MqResource implements IMqResource {
	private final Logger logger = LoggerFactory.getLogger(MqResource.class);
	private IHttpClient httpClient = null;
	private AtomicReference<List<String>> urlsG1 = new AtomicReference<>(new ArrayList<>());
	private AtomicReference<List<String>> urlsG2 = new AtomicReference<>(new ArrayList<>());
	private AtomicReference<List<String>> urlsOrigin = new AtomicReference<>(new ArrayList<>());
	// private AtomicInteger couter = new AtomicInteger(0);
	private Map<String, Long> failUrlG1 = new ConcurrentHashMap<>();
	private Map<String, Long> failUrlG2 = new ConcurrentHashMap<>();
	private ThreadPoolExecutor executor = null, executor1 = null;
	private AtomicLong counterG1 = new AtomicLong(0);
	private AtomicLong counterG2 = new AtomicLong(0);

	public MqResource(String url, long connectionTimeOut, long readTimeOut) {
		// this.httpClient = new HttpClient(connectionTimeOut, readTimeOut);
		this(new HttpClient(connectionTimeOut, readTimeOut), url);
	}

	public MqResource(IHttpClient httpClient, String url) {
		this.urlsG1.set(Arrays.asList(url.trim().split(",")));
		this.urlsG2.set(Arrays.asList(url.trim().split(",")));
		this.urlsOrigin.get().addAll(this.urlsG1.get());
		this.httpClient = httpClient;
		executor = new ThreadPoolExecutor(0, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(500),
				SoaThreadFactory.create("MqResource-heartbeat", true), new ThreadPoolExecutor.DiscardOldestPolicy());
		executor1 = new ThreadPoolExecutor(0, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(500),
				SoaThreadFactory.create("MqResource-mail", true), new ThreadPoolExecutor.DiscardOldestPolicy());

	}

	public void setUrls(List<String> urlsTempG1, List<String> urlsTempG2) {
		if (urlsTempG1 == null) {
			urlsTempG1 = new ArrayList<>();
		}
		if (urlsTempG2 == null) {
			urlsTempG2 = new ArrayList<>();
		}
		urlsG1.set(urlsTempG1);
		urlsG2.set(urlsTempG2);
		if (urlsTempG1.size() > 0) {
			int count = ((new BigDecimal(Math.random())).multiply(new BigDecimal(urlsTempG1.size()))).intValue();
			counterG1.set(count);
		}
		if (urlsTempG2.size() > 0) {
			int count = ((new BigDecimal(Math.random())).multiply(new BigDecimal(urlsTempG2.size()))).intValue();
			counterG2.set(count);
		}
		failUrlG1.clear();
		failUrlG2.clear();
	}

	protected String getHost(boolean isImportant) {
		List<String> urLst = (isImportant ? urlsG1.get() : urlsG2.get());
		int urlSize = urLst.size();
		if (urLst.size() == 0) {
			urLst = urlsOrigin.get();
			urlSize = urLst.size();
		}
		if (urlSize == 1)
			return urLst.get(0);

		int count = 0;
		int counter1 = 0;
		if (isImportant) {
			counter1 = (int) (counterG1.incrementAndGet() % urlSize);
		} else {
			counter1 = (int) (counterG2.incrementAndGet() % urlSize);
		}
		while (count < urlSize) {
			String url = doGetHost(urLst, counter1, isImportant);
			counter1 = (counter1 + 1) % urlSize;
			if (!Util.isEmpty(url)) {
				return url;
			}
			count++;
		}
		return urlsOrigin.get().get(counter1 % urlsOrigin.get().size());
	}

	protected String doGetHost(List<String> urLst, int count, boolean isImportant) {
		String temp = urLst.get(count);
		Long t = 0L;
		if (isImportant) {
			t = failUrlG1.get(temp);
		} else {
			t = failUrlG2.get(temp);
		}
		if (t != null) {
			long currentTime = System.currentTimeMillis();
			if (t > currentTime - 10 * 1000) {
				return "";
			}
		}
		return temp;
	}

	public long register(ConsumerRegisterRequest request) {
		if (request == null) {
			return 0;
		}
		String url = MqConstanst.CONSUMERPRE + "/register";
		ConsumerRegisterResponse response = null;
		try {
			response = post(request, url, 10, ConsumerRegisterResponse.class, true);
			if (response != null && !Util.isEmpty(response.getMsg())) {
				logger.warn(response.getMsg());
			}
		} catch (Exception e) {
			CatRequest request2 = new CatRequest();
			request2.setMethod("register");
			request2.setJson(JsonUtil.toJson(request));
			request2.setMsg(e.getMessage());
			addCat(request2);
			throw new RuntimeException(request.getName() + "注册失败," + e.getMessage() + "！");
		}
		return response.getId();
	}

	public void publishAndUpdateResultFailMsg(FailMsgPublishAndUpdateResultRequest request) {
		if (request == null) {
			return;
		}
		String url = MqConstanst.CONSUMERPRE + "/publishAndUpdateResultFailMsg";
		try {
			post(request, url, 2, FailMsgPublishAndUpdateResultResponse.class, true);
		} catch (Exception e) {
			CatRequest request2 = new CatRequest();
			request2.setMethod("register");
			request2.setJson(JsonUtil.toJson(request));
			request2.setMsg(e.getMessage());
			addCat(request2);
		}
	}

	public void deRegister(ConsumerDeRegisterRequest request) {
		if (request == null) {
			return;
		}
		String url = MqConstanst.CONSUMERPRE + "/deRegister";
		post(request, url, 10, ConsumerDeRegisterResponse.class, true);
	}

	public GetMetaGroupResponse getMetaGroup(GetMetaGroupRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.METAPRE + "/getMetaGroup";
		GetMetaGroupResponse response = post(request, url, 10, GetMetaGroupResponse.class, false);
		return response;
	}

	public GetTopicResponse getTopic(GetTopicRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.METAPRE + "/getTopic";
		GetTopicResponse response = post(request, url, 2, GetTopicResponse.class, false);
		return response;
	}

	public GetGroupTopicResponse getGroupTopic(GetGroupTopicRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.METAPRE + "/getGroupTopic";
		GetGroupTopicResponse response = post(request, url, 2, GetGroupTopicResponse.class, false);
		return response;
	}

	public void addCat(CatRequest request) {
		if (request == null) {
			return;
		}
		executor1.submit(new Runnable() {
			@Override
			public void run() {
				String url = MqConstanst.TOOLPRE + "/addCat";
				try {
					post(request, url, 1, CatResponse.class, false);
				} catch (Exception e) {
					// TODO: handle exception
				}
			}
		});

	}

	public boolean publish(PublishMessageRequest request) {
		return publish(request, 10);
	}

	public boolean publish(PublishMessageRequest request, int retryTimes) {
		if (request == null) {
			return true;
		}
		Transaction transaction = Tracer.newTransaction("mq-client-publish", request.getTopicName());
		Timer.Context timer1 = MetricSingleton.getMetricRegistry()
				.timer("mq.client.publish.time?topic=" + request.getTopicName()).time();
		try {
			String url = MqConstanst.CONSUMERPRE + "/publish";
			long start = System.nanoTime();
			PublishMessageResponse response = post(request, url, retryTimes, PublishMessageResponse.class, true);
			long end = System.nanoTime();
			if (response.getTime() > 0) {
				long t = end - start - response.getTime();
				t = (t - t % 1000000) / 1000000;
				MetricSingleton.getMetricRegistry()
						.histogram("mq.client.publish.network.time?topic=" + request.getTopicName()).update(t);
			}
			transaction.setStatus(Transaction.SUCCESS);
			if (!response.isSuc()) {
				String json = JsonUtil.toJson(request);
				logger.error(response.getMsg());
				CatRequest request2 = new CatRequest();
				request2.setMethod("publish_fail");
				request2.setJson(json);
				request2.setMsg(response.getMsg());
				addCat(request2);

				SendMailRequest mailRequest = new SendMailRequest();
				mailRequest.setSubject("客户端：" + request.getClientIp() + ",Topic：" + request.getTopicName() + "发送失败！");
				mailRequest.setContent("消息发送失败，" + response.getMsg() + ",消息体是：" + json);
				mailRequest.setType(2);
				mailRequest.setKey("topic:" + request.getTopicName() + "-发送失败！");
				sendMail(mailRequest);
			}
			return response.isSuc();
		} catch (Exception e) {
			MetricSingleton.getMetricRegistry().counter("mq.client.publish.fail.count?topic=" + request.getTopicName())
					.inc();
			logger.error("publish_error", e);
			String json = JsonUtil.toJson(request);
			transaction.setStatus(e);
			CatRequest request2 = new CatRequest();
			request2.setMethod("publish");
			request2.setJson(json);
			request2.setMsg(e.getMessage());
			addCat(request2);

			SendMailRequest mailRequest = new SendMailRequest();
			mailRequest.setSubject("客户端：" + request.getClientIp() + ",Topic：" + request.getTopicName() + "发送失败！");
			mailRequest.setContent("消息发送异常，" + ",消息体是：" + json + ",异常原因是：" + e.getMessage());
			mailRequest.setType(2);
			sendMail(mailRequest);
			return false;
		} finally {
			transaction.complete();
			timer1.stop();
		}
	}

	public void commitOffset(CommitOffsetRequest request) {
		if (request == null) {
			return;
		}
		String url = MqConstanst.CONSUMERPRE + "/commitOffset";
		try {
			post(request, url, 10, CommitOffsetResponse.class, false);
		} catch (Exception e) {
			CatRequest request2 = new CatRequest();
			request2.setMethod("commitOffset");
			request2.setJson(JsonUtil.toJson(request));
			request2.setMsg(e.getMessage());
			addCat(request2);
		}
	}

	public ConsumerGroupRegisterResponse registerConsumerGroup(ConsumerGroupRegisterRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.CONSUMERPRE + "/registerConsumerGroup";
		try {
			ConsumerGroupRegisterResponse response = post(request, url, 2, ConsumerGroupRegisterResponse.class, true);
			boolean flag = response != null && response.isSuc();
			if (!flag && response != null) {
				logger.error("registerConsumerGroup_error," + response.getMsg());
			}
			return response;
		} catch (Exception e) {
			CatRequest request2 = new CatRequest();
			request2.setMethod("registerConsumerGroup");
			request2.setJson(JsonUtil.toJson(request));
			request2.setMsg(e.getMessage());
			addCat(request2);
			logger.error("registerConsumerGroup_error", e);
			throw e;
		}

	}

	public void heartbeat(HeartbeatRequest request) {
		if (request == null) {
			return;
		}
		String url = MqConstanst.CONSUMERPRE + "/heartbeat";
		try {
			post(request, url, 3, HeartbeatResponse.class, false);
		} catch (Exception e) {
			// TODO: handle exception
		}
	}

	public GetConsumerGroupResponse getConsumerGroup(GetConsumerGroupRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.CONSUMERPRE + "/getConsumerGroupPolling";
		return post(request, url, 10, GetConsumerGroupResponse.class, true);
	}

	public GetMessageCountResponse getMessageCount(GetMessageCountRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.CONSUMERPRE + "/getMessageCount";
		return post(request, url, 2, GetMessageCountResponse.class, true);
	}

	public PullDataResponse pullData(PullDataRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.CONSUMERPRE + "/pullData";
		try {
			return post(request, url, 2, PullDataResponse.class, true);
		} catch (Exception e) {
			CatRequest request2 = new CatRequest();
			request2.setMethod("pullData");
			request2.setJson(JsonUtil.toJson(request));
			request2.setMsg(e.getMessage());
			addCat(request2);
			throw e;
		}
	}

	public GetTopicQueueIdsResponse getTopicQueueIds(GetTopicQueueIdsRequest request) {
		if (request == null) {
			return null;
		}
		String url = MqConstanst.METAPRE + "/getTopicQueueIds";
		try {
			return post(request, url, 2, GetTopicQueueIdsResponse.class, false);
		} catch (Exception e) {
			CatRequest request2 = new CatRequest();
			request2.setMethod("getTopicQueueIds");
			request2.setJson(JsonUtil.toJson(request));
			request2.setMsg(e.getMessage());
			addCat(request2);
			throw e;
		}
	}

	public void addLog(LogRequest request) {
		if (request == null) {
			return;
		}
		executor.execute(new Runnable() {
			@Override
			public void run() {
				String url = MqConstanst.TOOLPRE + "/addLog";
				try {
					post(request, url, 1, LogResponse.class, false);
				} catch (Exception e) {

				}
			}
		});

	}

	public void addOpLog(OpLogRequest request) {
		if (request == null) {
			return;
		}
		executor1.submit(new Runnable() {
			@Override
			public void run() {
				String url = MqConstanst.TOOLPRE + "/addOpLog";
				try {
					post(request, url, 5, OpLogResponse.class, false);
				} catch (Exception e) {
					// TODO: handle exception
				}

			}
		});

	}

	public void sendMail(SendMailRequest request) {
		if (request == null) {
			return;
		}
		executor1.submit(new Runnable() {
			@Override
			public void run() {
				String url = MqConstanst.TOOLPRE + "/sendMail";
				try {
					post(request, url, 1, SendMailResponse.class, false);
				} catch (Exception e) {
					// TODO: handle exception
				}
			}
		});
	}

	protected <T> T post(Object request, String path, int tryCount, Class<T> class1, boolean isImportant) {
		T response = null;
		int count = 0;
		Exception last = null;
		String url = null;
		while (response == null && count < tryCount) {
			String host = getHost(isImportant);
			url = host + path;
			try {
				response = httpClient.post(url, request, class1);
				last = null;
			} catch (IOException e) {
				if (!(url.indexOf(MqConstanst.CONSUMERPRE + "/heartbeat") != -1
						|| url.indexOf(MqConstanst.CONSUMERPRE + "/getMetaGroup") != -1)) {
					logger.error("访问" + url + "异常,access_error", e);
				}
				last = e;
			} catch (BrokerException e) {
				last = e;
			} catch (Exception e) {
				last = e;
			} finally {
				if (response != null) {
					if (isImportant) {
						failUrlG1.put(host, System.currentTimeMillis() - 10 * 1000);
					} else {
						failUrlG2.put(host, System.currentTimeMillis() - 10 * 1000);
					}
					if (response instanceof PublishMessageResponse) {
						PublishMessageResponse response2 = ((PublishMessageResponse) response);
						if (response2.getSleepTime() > 0) {
							response = null;
							logger.info(response2.getMsg());
							Util.sleep(response2.getSleepTime());
							// 这个不算重试，只是降速
							count--;
						}
					} else {
						BaseResponse baseResponse = (BaseResponse) response;
						if (!baseResponse.isSuc() && baseResponse.getCode() == MqConstanst.NO) {
							response = null;
							Util.sleep(1000);
						} else {
							if (!baseResponse.isSuc()) {
								logger.error(baseResponse.getMsg());
							}
						}
					}
				} else {
					// response 等于null 说明接口调用失败了。此时需要将url 放入失败接口中。
					if (isImportant) {
						failUrlG1.put(host, System.currentTimeMillis());
					} else {
						failUrlG2.put(host, System.currentTimeMillis());
					}
					Util.sleep(500);
				}
			}
			count++;
		}
		if (last != null) {
			if (!url.endsWith("/sendMail") && (path + "").endsWith("/publish")) {
				SendMailRequest request2 = new SendMailRequest();
				request2.setSubject("客户端:" + IPUtil.getLocalIP() + "，访问" + url + "报错");
				request2.setContent(last.getMessage());
				request2.setType(1);
				request2.setKey("访问" + url + "报错");
				sendMail(request2);
			}
			throw new RuntimeException(last);
		}
		return response;
	}

	@Override
	public String getBrokerIp() {
		String url = getHost(false)+"/api/client/tool/getIp";
		try {
			return httpClient.get(url);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			return "";
		}
	}

}