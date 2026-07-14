package com.agora.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * THE canonical fan-out debate, runnable.
 *
 * Fan-out-on-WRITE: post is pushed into every follower's feed list at write
 * time. Reads are O(1) (LRANGE own list) but a celebrity with 10M followers
 * costs 10M writes per post.
 *
 * Fan-out-on-READ: post is stored once on the seller; each reader merges the
 * timelines of everyone they follow at read time. Writes are O(1), reads cost
 * a k-way merge.
 *
 * HYBRID (default): sellers under FEED_CELEB_THRESHOLD fan out on write;
 * "celebrities" above it are merged at read. FANOUT_MODE=write|read|hybrid
 * flips strategy live (flagd integration is the Phase-7 upgrade of this env).
 */
@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final int celebThreshold;
    private final String mode;

    public FeedController(StringRedisTemplate redis,
                          @Value("${feed.celeb-threshold}") int celebThreshold,
                          @Value("${feed.mode}") String mode) {
        this.redis = redis;
        this.celebThreshold = celebThreshold;
        this.mode = mode;
    }

    @PostMapping("/follow")
    public Map<String, Object> follow(@RequestBody Map<String, String> req) {
        redis.opsForSet().add("followers:" + req.get("seller"), req.get("follower"));
        redis.opsForSet().add("following:" + req.get("follower"), req.get("seller"));
        return Map.of("ok", true);
    }

    @PostMapping("/post")
    public Map<String, Object> post(@RequestBody Map<String, String> req,
                                    @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String seller)
            throws Exception {
        String post = json.writeValueAsString(Map.of(
                "seller", seller, "text", req.get("text"), "ts", System.currentTimeMillis()));
        redis.opsForList().leftPush("posts:" + seller, post);
        redis.opsForList().trim("posts:" + seller, 0, 199);

        Long followers = redis.opsForSet().size("followers:" + seller);
        boolean fanOutOnWrite = switch (mode) {
            case "write" -> true;
            case "read" -> false;
            default -> followers != null && followers < celebThreshold;
        };
        long pushed = 0;
        if (fanOutOnWrite) {
            for (String f : Objects.requireNonNullElse(redis.opsForSet().members("followers:" + seller), Set.<String>of())) {
                redis.opsForList().leftPush("feed:" + f, post);
                redis.opsForList().trim("feed:" + f, 0, 99);
                pushed++;
            }
        } else {
            redis.opsForSet().add("celebs", seller); // readers must merge this seller
        }
        return Map.of("fanout", fanOutOnWrite ? "write" : "read", "pushedTo", pushed, "followers",
                followers == null ? 0 : followers);
    }

    @GetMapping("/{user}")
    public Map<String, Object> feed(@PathVariable String user) throws Exception {
        // Precomputed part (fan-out-on-write)
        List<String> precomputed = Objects.requireNonNullElse(
                redis.opsForList().range("feed:" + user, 0, 49), List.of());
        List<Map<String, Object>> merged = new ArrayList<>();
        for (String p : precomputed) merged.add(json.readValue(p, Map.class));

        // Read-time merge for followed celebrities
        Set<String> following = Objects.requireNonNullElse(redis.opsForSet().members("following:" + user), Set.of());
        Set<String> celebs = Objects.requireNonNullElse(redis.opsForSet().members("celebs"), Set.of());
        List<String> mergedFrom = new ArrayList<>();
        for (String seller : following) {
            if (celebs.contains(seller)) {
                mergedFrom.add(seller);
                for (String p : Objects.requireNonNullElse(redis.opsForList().range("posts:" + seller, 0, 19), List.<String>of())) {
                    merged.add(json.readValue(p, Map.class));
                }
            }
        }
        merged.sort((a, b) -> Long.compare((long) b.get("ts"), (long) a.get("ts")));
        return Map.of("user", user, "mergedCelebrities", mergedFrom,
                "items", merged.subList(0, Math.min(merged.size(), 50)));
    }
}
