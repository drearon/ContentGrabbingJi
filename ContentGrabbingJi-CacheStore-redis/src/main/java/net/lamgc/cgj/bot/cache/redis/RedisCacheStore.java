/*
 * Copyright (C) 2020  LamGC
 *
 * ContentGrabbingJi is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * ContentGrabbingJi is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.lamgc.cgj.bot.cache.redis;

import net.lamgc.cgj.bot.cache.CacheKey;
import net.lamgc.cgj.bot.cache.CacheStore;
import redis.clients.jedis.Jedis;

import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author LamGC
 */
public abstract class RedisCacheStore<V> implements CacheStore<V> {

    /**
     * 获取 Key 前缀.
     * <p>key = getKeyPrefix() + key
     * @param cacheKey CacheKey 对象.
     * @return 返回 Key 前缀.
     */
    protected String getKeyString(CacheKey cacheKey) {
        return getKeyPrefix() + cacheKey.join(RedisUtils.KEY_SEPARATOR);
    }

    /**
     * 获取 Key 的完整前缀.
     * @return 返回完整前缀.
     */
    protected abstract String getKeyPrefix();

    @Override
    public boolean setTimeToLive(CacheKey key, long ttl) {
        String keyString = getKeyString(key);
        return RedisConnectionPool.executeRedis(jedis -> {
            Long result;
            if (ttl >= 0) {
                result = jedis.pexpire(keyString, ttl);
            } else {
                result = jedis.persist(keyString);
            }
            return result == RedisUtils.RETURN_CODE_OK;
        });
    }

    @Override
    public long getTimeToLive(CacheKey key) {
        return RedisConnectionPool.executeRedis(jedis -> {
            Long ttl = jedis.pttl(getKeyString(key));
            return ttl < 0 ? -1 : ttl;
        });
    }

    @Override
    public long size() {
        return RedisConnectionPool.executeRedis(Jedis::dbSize);
    }

    @Override
    public boolean clear() {
        return RedisConnectionPool.executeRedis(jedis -> RedisUtils.isOk(jedis.flushDB()));
    }

    @Override
    public boolean exists(CacheKey key) {
        return RedisConnectionPool.executeRedis(jedis -> jedis.exists(getKeyString(key)));
    }

    @Override
    public boolean remove(CacheKey key) {
        return RedisConnectionPool.executeRedis(jedis -> jedis.del(getKeyString(key)) == RedisUtils.RETURN_CODE_OK);
    }

    @Override
    public Set<String> keySet() {
        Set<String> keys = RedisConnectionPool.executeRedis(jedis -> jedis.keys(RedisUtils.KEY_PATTERN_ALL));
        final int prefixLength = getKeyPrefix().length();
        Set<String> newKeys = new HashSet<>();
        for (String key : keys) {
            newKeys.add(key.substring(prefixLength));
        }
        return newKeys;
    }

}
