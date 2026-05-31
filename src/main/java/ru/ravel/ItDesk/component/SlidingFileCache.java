package ru.ravel.ItDesk.component;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;


@Component
@RequiredArgsConstructor
public class SlidingFileCache {

	private final RedisTemplate<String, byte[]> redis;

	@Value("${spring.data.redis.ttl-days}")
	private Integer ttlDays;


	public byte[] getOrLoad(String namespace, String uuid, Supplier<byte[]> loader) {
		Duration ttl = Duration.ofDays(ttlDays == null || ttlDays < 1 ? 7 : ttlDays);
		String key = namespace + "::" + uuid;
		byte[] cached = redis.opsForValue().get(key);
		if (cached != null) {
			redis.expire(key, ttl);
			return cached;
		}
		byte[] loaded = loader.get();
		if (loaded == null || loaded.length == 0) {
			return loaded;
		}
		redis.opsForValue().set(key, loaded, ttl);
		return loaded;
	}


	public void evict(String namespace, String uuid) {
		redis.delete(namespace + "::" + uuid);
	}

}