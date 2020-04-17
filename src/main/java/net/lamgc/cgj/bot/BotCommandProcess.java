package net.lamgc.cgj.bot;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import net.lamgc.cgj.Main;
import net.lamgc.cgj.bot.cache.*;
import net.lamgc.cgj.bot.event.BotEventHandler;
import net.lamgc.cgj.bot.sort.PreLoadDataComparator;
import net.lamgc.cgj.pixiv.PixivDownload;
import net.lamgc.cgj.pixiv.PixivSearchBuilder;
import net.lamgc.cgj.pixiv.PixivURL;
import net.lamgc.cgj.util.URLs;
import net.lamgc.utils.base.runner.Argument;
import net.lamgc.utils.base.runner.Command;
import net.lamgc.utils.event.EventExecutor;
import net.lz1998.cq.utils.CQCode;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BotCommandProcess {

    private final static PixivDownload pixivDownload = new PixivDownload(Main.cookieStore, Main.proxy);

    private final static Logger log = LoggerFactory.getLogger(BotCommandProcess.class.getSimpleName());

    private final static File imageStoreDir = new File(System.getProperty("cgj.cqRootDir"), "data/image/cgj/");
    public final static Properties globalProp = new Properties();
    private final static Gson gson = new GsonBuilder()
            .serializeNulls()
            .create();

    private final static Hashtable<String, File> imageCache = new Hashtable<>();
    private final static CacheStore<JsonElement> illustInfoCache = new JsonRedisCacheStore(BotEventHandler.redisServer, "illustInfo", gson);
    private final static CacheStore<JsonElement> illustPreLoadDataCache = new HotDataCacheStore<>(
            new JsonRedisCacheStore(BotEventHandler.redisServer, "illustPreLoadData", gson),
            new LocalHashCacheStore<>(), 3600000, 900000);
    private final static CacheStore<JsonElement> searchBodyCache = new JsonRedisCacheStore(BotEventHandler.redisServer, "searchBody", gson);
    private final static CacheStore<List<JsonObject>> rankingCache = new JsonObjectRedisListCacheStore(BotEventHandler.redisServer, "ranking", gson);
    private final static CacheStore<List<String>> pagesCache = new RedisPoolCacheStore<List<String>>(BotEventHandler.redisServer, "imagePages") {
        @Override
        protected String parse(List<String> dataObj) {
            return gson.toJson(dataObj);
        }

        @Override
        protected List<String> analysis(String dataStr) {
            return gson.fromJson(dataStr, new TypeToken<List<String>>(){}.getType());
        }
    };

    /**
     * 图片异步缓存执行器
     */
    private final static EventExecutor imageCacheExecutor = new EventExecutor(new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() >= 2 ? 2 : 1,
            (int) Math.ceil(Runtime.getRuntime().availableProcessors() / 2F),
            5L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadFactoryBuilder()
                    .setNameFormat("imageCacheThread-%d")
                    .build(),
            new ThreadPoolExecutor.DiscardOldestPolicy()
    ));

    private final static RankingUpdateTimer updateTimer = new RankingUpdateTimer();

    public static void initialize() {
        log.info("正在初始化...");

        File globalPropFile = new File("./global.properties");
        if(globalPropFile.exists() && globalPropFile.isFile()) {
            log.info("正在加载全局配置文件...");
            try {
                globalProp.load(new FileInputStream(globalPropFile));
                log.info("全局配置文件加载完成.");
            } catch (IOException e) {
                log.error("加载全局配置文件时发生异常", e);
            }
        } else {
            log.info("未找到全局配置文件，跳过加载.");
        }

        try {
            imageCacheExecutor.addHandler(new ImageCacheHandler());
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        updateTimer.schedule(null);
        log.info("初始化完成.");
    }

    /**
     * 执行定时更新任务
     * @param queryTime 指定更新日期
     */
    public static void runUpdateTimer(Date queryTime) {
        log.info("正在手动触发排行榜更新任务...");
        updateTimer.now(queryTime);
        log.info("任务执行结束.");
    }

    @Command(defaultCommand = true)
    public static String help() {
        StringBuilder helpStrBuilder = new StringBuilder();
        helpStrBuilder.append("CGJ Bot使用指南").append("\n");
        helpStrBuilder.append("目前可用的命令:").append("\n");
        helpStrBuilder.append("\t").append("ranking - 获取今天或指定日期排行榜的前10名作品").append("\n");
        helpStrBuilder.append("\t\t").append("-date - 指定查询日期(年-月-日)").append("\n");
        helpStrBuilder.append("\t").append("search - 搜索指定关键词并显示前10个作品").append("\n");
        helpStrBuilder.append("\t\t").append("-content - 搜索内容").append("\n");
        helpStrBuilder.append("\t").append("artworks - 获取作品的Pixiv页面").append("\n");
        helpStrBuilder.append("\t\t").append("-id - 作品id").append("\n");
        return helpStrBuilder.toString();
    }

    @Command
    public static String ranking(
            @Argument(force = false, name = "date") Date queryTime,
            @Argument(force = false, name = "mode", defaultValue = "DAILY") String contentMode,
            @Argument(force = false, name = "type", defaultValue = "ILLUST") String contentType
    ) {
        Date queryDate = queryTime;
        if (queryDate == null) {
            queryDate = new Date();
            GregorianCalendar gregorianCalendar = new GregorianCalendar();
            log.debug("CurrentDate: {}", queryDate);
            gregorianCalendar.setTime(queryDate);
            if (gregorianCalendar.get(Calendar.HOUR_OF_DAY) < 12) {
                gregorianCalendar.add(Calendar.DATE, -2);
            } else {
                gregorianCalendar.add(Calendar.DATE, -1);
            }
            queryDate = gregorianCalendar.getTime();
        } else {
            if(new Date().before(queryDate)) {
                log.warn("查询的日期过早, 无法查询排行榜.");
                return "查询日期过早, 暂未更新指定日期的排行榜!";
            }
        }

        PixivURL.RankingMode mode = PixivURL.RankingMode.MODE_DAILY;
        try {
            mode = PixivURL.RankingMode.valueOf("MODE_" + contentMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的RankingMode值: {}", contentMode);
        }

        PixivURL.RankingContentType type = PixivURL.RankingContentType.TYPE_ILLUST;
        try {
            type = PixivURL.RankingContentType.valueOf("TYPE_" + contentType.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的RankingContentType值: {}", contentType);
        }

        if(!type.isSupportedMode(mode)) {
            log.warn("RankingContentType不支持指定的RankingMode.(ContentType: {}, RankingMode: {})",
                    type.name(), mode.name());
            return "不支持的内容类型或模式!";
        }

        StringBuilder resultBuilder = new StringBuilder(mode.name() + " - 以下是 ").append(new SimpleDateFormat("yyyy-MM-dd").format(queryDate)).append(" 的Pixiv插画排名榜前十名：\n");
        try {
            int index = 0;
            int itemLimit = 10;
            String itemLimitPropertyKey = "ranking.ItemCountLimit";
            try {
                itemLimit = Integer.parseInt(globalProp.getProperty(itemLimitPropertyKey, "10"));
            } catch(NumberFormatException e) {
                log.warn("配置项 {} 的参数值格式有误!", itemLimitPropertyKey);
            }

            int imageLimit = 3;
            String imageLimitPropertyKey = "ranking.imageCountLimit";
            try {
                imageLimit = Integer.parseInt(globalProp.getProperty(imageLimitPropertyKey, "3"));
            } catch(NumberFormatException e) {
                log.warn("配置项 {} 的参数值格式有误!", imageLimitPropertyKey);
            }

            //TODO(LamGC, 2020.4.11): 将JsonRedisCacheStore更改为使用Redis的List集合, 以提高性能
            List<JsonObject> rankingInfoList = getRankingInfoByCache(type, mode, queryDate, 1, Math.max(0, itemLimit), false);
            if(rankingInfoList.isEmpty()) {
                return "无法查询排行榜，可能排行榜尚未更新。";
            }

            for (JsonObject rankInfo : rankingInfoList) {
                index++;
                int rank = rankInfo.get("rank").getAsInt();
                int illustId = rankInfo.get("illust_id").getAsInt();
                int authorId = rankInfo.get("user_id").getAsInt();
                int pagesCount = rankInfo.get("illust_page_count").getAsInt();
                String authorName = rankInfo.get("user_name").getAsString();
                String title = rankInfo.get("title").getAsString();
                resultBuilder.append(rank).append(". (id: ").append(illustId).append(") ").append(title)
                        .append("(Author: ").append(authorName).append(",").append(authorId).append(") ").append(pagesCount).append("p.\n");
                if (index <= imageLimit) {
                    resultBuilder.append(getImageById(illustId, PixivDownload.PageQuality.REGULAR, 1)).append("\n");
                }
            }
        } catch (IOException e) {
            log.error("消息处理异常", e);
            return "排名榜获取失败！详情请查看机器人控制台。";
        }
        return resultBuilder.append("如查询当前时间获取到昨天时间，则今日排名榜未更新。").toString();
    }

    @Command(commandName = "userArt")
    public static String userArtworks() {

        return "功能未完成";
    }

    private final static Object searchCacheLock = new Object();
    @Command
    public static String search(@Argument(name = "content") String content,
                                @Argument(name = "type", force = false) String type,
                                @Argument(name = "area", force = false) String area,
                                @Argument(name = "in", force = false) String includeKeywords,
                                @Argument(name = "ex", force = false) String excludeKeywords,
                                @Argument(name = "contentOption", force = false) String contentOption,
                                @Argument(name = "page", force = false, defaultValue = "1") int pagesIndex
    ) throws IOException {
        log.info("正在执行搜索...");
        PixivSearchBuilder searchBuilder = new PixivSearchBuilder(Strings.isNullOrEmpty(content) ? "" : content);
        if (type != null) {
            try {
                searchBuilder.setSearchType(PixivSearchBuilder.SearchType.valueOf(type.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("不支持的SearchType: {}", type);
            }
        }
        if (area != null) {
            try {
                searchBuilder.setSearchArea(PixivSearchBuilder.SearchArea.valueOf(area));
            } catch (IllegalArgumentException e) {
                log.warn("不支持的SearchArea: {}", area);
            }
        }
        if (contentOption != null) {
            try {
                searchBuilder.setSearchContentOption(PixivSearchBuilder.SearchContentOption.valueOf(contentOption));
            } catch (IllegalArgumentException e) {
                log.warn("不支持的SearchContentOption: {}", contentOption);
            }
        }

        if (!Strings.isNullOrEmpty(includeKeywords)) {
            for (String keyword : includeKeywords.split(";")) {
                searchBuilder.removeExcludeKeyword(keyword);
                searchBuilder.addIncludeKeyword(keyword);
                log.debug("已添加关键字: {}", keyword);
            }
        }
        if (!Strings.isNullOrEmpty(excludeKeywords)) {
            for (String keyword : excludeKeywords.split(";")) {
                searchBuilder.removeIncludeKeyword(keyword);
                searchBuilder.addExcludeKeyword(keyword);
                log.debug("已添加排除关键字: {}", keyword);
            }
        }

        log.info("正在搜索作品, 条件: {}", searchBuilder.getSearchCondition());

        String requestUrl = searchBuilder.buildURL();
        log.debug("RequestUrl: {}", requestUrl);
        JsonObject resultBody = null;
        if(!searchBodyCache.exists(requestUrl)) {
            synchronized (searchCacheLock) {
                if (!searchBodyCache.exists(requestUrl)) {
                    log.debug("searchBody缓存失效, 正在更新...");
                    JsonObject jsonObject;
                    HttpGet httpGetRequest = pixivDownload.createHttpGetRequest(requestUrl);
                    HttpResponse response = pixivDownload.getHttpClient().execute(httpGetRequest);

                    String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    log.debug("ResponseBody: {}", responseBody);
                    jsonObject = gson.fromJson(responseBody, JsonObject.class);

                    if (jsonObject.get("error").getAsBoolean()) {
                        log.error("接口请求错误, 错误信息: {}", jsonObject.get("message").getAsString());
                        return "处理命令时发生错误！";
                    }

                    long expire = 7200 * 1000;
                    String propValue = globalProp.getProperty("cache.searchBody.expire", "7200000");
                    try {
                        expire = Long.parseLong(propValue);
                    } catch (Exception e) {
                        log.warn("全局配置项 \"{}\" 值非法, 已使用默认值: {}", propValue, expire);
                    }
                    resultBody = jsonObject.getAsJsonObject().getAsJsonObject("body");
                    searchBodyCache.update(requestUrl, jsonObject, expire);
                    log.debug("searchBody缓存已更新(有效时间: {})", expire);
                } else {
                    log.debug("搜索缓存命中.");
                }
            }
        } else {
            log.debug("搜索缓存命中.");
        }

        if(Objects.isNull(resultBody)) {
            resultBody = searchBodyCache.getCache(requestUrl).getAsJsonObject().getAsJsonObject("body");
        }

        StringBuilder result = new StringBuilder("内容 " + content + " 的搜索结果：\n");
        log.debug("正在处理信息...");
        int limit = 8;
        try {
            limit = Integer.parseInt(globalProp.getProperty("search.ItemCountLimit", "8"));
        } catch (Exception e) {
            log.warn("参数转换异常!将使用默认值(" + limit + ")", e);
        }
        for (PixivSearchBuilder.SearchArea searchArea : PixivSearchBuilder.SearchArea.values()) {
            if (!resultBody.has(searchArea.jsonKey) || resultBody.getAsJsonObject(searchArea.jsonKey).getAsJsonArray("data").size() == 0) {
                log.debug("返回数据不包含 {}", searchArea.jsonKey);
                continue;
            }
            JsonArray illustsArray = resultBody
                    .getAsJsonObject(searchArea.jsonKey).getAsJsonArray("data");
            ArrayList<JsonElement> illustsList = new ArrayList<>();
            illustsArray.forEach(illustsList::add);
            illustsList.sort(new PreLoadDataComparator(PreLoadDataComparator.Attribute.LIKE));

            log.info("已找到与 {} 相关插图信息({})：", content, searchArea.name().toLowerCase());
            int count = 1;
            for (JsonElement jsonElement : illustsList) {
                if (count > limit) {
                    break;
                }
                JsonObject illustObj = jsonElement.getAsJsonObject();
                if (!illustObj.has("illustId")) {
                    continue;
                }
                int illustId = illustObj.get("illustId").getAsInt();
                StringBuilder builder = new StringBuilder("[");
                illustObj.get("tags").getAsJsonArray().forEach(el -> builder.append(el.getAsString()).append(", "));
                builder.replace(builder.length() - 2, builder.length(), "]");
                log.debug("{} ({} / {})\n\t作品id: {}, \n\t作者名(作者id): {} ({}), \n\t作品标题: {}, \n\t作品Tags: {}, \n\t页数: {}, \n\t作品链接: {}",
                        searchArea.name(),
                        count,
                        illustsList.size(),
                        illustId,
                        illustObj.get("userName").getAsString(),
                        illustObj.get("userId").getAsInt(),
                        illustObj.get("illustTitle").getAsString(),
                        builder,
                        illustObj.get("pageCount").getAsInt(),
                        PixivURL.getPixivRefererLink(illustId)
                );

                //pageCount

                String imageMsg = getImageById(illustId, PixivDownload.PageQuality.REGULAR, 1);
                if (isNoSafe(illustId, globalProp, true)) {
                    log.warn("作品Id {} 为R-18作品, 跳过.", illustId);
                    continue;
                }

                result.append(searchArea.name()).append(" (").append(count).append(" / ").append(limit).append(")\n\t作品id: ").append(illustId)
                        .append(", \n\t作者名: ").append(illustObj.get("userName").getAsString())
                        .append("\n\t作品标题: ").append(illustObj.get("illustTitle").getAsString())
                        .append("\n\t作品页数: ").append(illustObj.get("pageCount").getAsInt())
                        .append("\n").append(imageMsg).append("\n");
                count++;
            }
            if (count > limit) {
                break;
            }
        }
        return Strings.nullToEmpty(result.toString()) + "预览图片并非原图，使用“.cgj image -id 作品id”获取原图";
    }

    @Command(commandName = "pages")
    public static String getPagesList(@Argument(name = "id") int illustId, @Argument(name = "quality", force = false) PixivDownload.PageQuality quality) {
        try {
            List<String> pagesList = PixivDownload.getIllustAllPageDownload(pixivDownload.getHttpClient(), pixivDownload.getCookieStore(), illustId, quality);
            StringBuilder builder = new StringBuilder("作品ID ").append(illustId).append(" 共有").append(pagesList.size()).append("页：").append("\n");
            int index = 0;
            for (String link : pagesList) {
                builder.append("Page ").append(++index).append(": ").append(link).append("\n");
            }
            return builder.toString();
        } catch (IOException e) {
            log.error("获取作品所有页面下载链接失败！", e);
            return "发生错误，无法完成命令";
        }
    }

    @Command(commandName = "artworks")
    public static String artworksLink(@Argument(name = "id") int illustId) {
        try {
            if (isNoSafe(illustId, globalProp, false)) {
                log.warn("作品Id {} 已被屏蔽.", illustId);
                return "由于相关设置，该作品已被屏蔽！";
            }
        } catch (IOException e) {
            log.error("获取作品信息失败!", e);
            return "作品信息无法获取！";
        }
        return PixivURL.getPixivRefererLink(illustId);
    }

    /**
     * 通过illustId获取作品图片
     * @param illustId 作品Id
     * @param quality 图片质量
     * @param pageIndex 指定页面索引, 从1开始
     * @return 如果成功, 返回BotCode, 否则返回错误信息.
     */
    @Command(commandName = "image")
    public static String getImageById(@Argument(name = "id") int illustId,
                                                   @Argument(name = "quality", force = false) PixivDownload.PageQuality quality,
                                                   @Argument(name = "page", force = false, defaultValue = "1") int pageIndex) {
        log.debug("IllustId: {}, Quality: {}, PageIndex: {}", illustId, quality.name(), pageIndex);
        List<String> pagesList;
        try {
            pagesList = getIllustPages(illustId, quality, false);
        } catch (IOException e) {
            log.error("获取下载链接列表时发生异常", e);
            return "发生网络异常，无法获取图片！";
        }

        if(log.isDebugEnabled()) {
            StringBuilder logBuilder = new StringBuilder("作品Id {} 所有页面下载链接: \n");
            AtomicInteger index = new AtomicInteger();
            pagesList.forEach(item -> logBuilder.append(index.incrementAndGet()).append(". ").append(item).append("\n"));
            log.debug(logBuilder.toString());
        }

        if (pagesList.size() < pageIndex || pageIndex <= 0) {
            log.warn("指定的页数超出了总页数({} / {})", pageIndex, pagesList.size());
            return "指定的页数超出了范围(总共 " + pagesList.size() + " 页)";
        }

        try {
            if (isNoSafe(illustId, globalProp, false)) {
                log.warn("作品 {} 存在R-18内容且设置\"image.allowR18\"为false，将屏蔽该作品不发送.", illustId);
                return "（根据设置，该作品已被屏蔽！）";
            }
        } catch (IOException e) {
            log.warn("作品信息无法获取!", e);
            return "发生网络异常，无法获取图片！";
        }

        String downloadLink = pagesList.get(pageIndex - 1);
        String fileName = URLs.getResourceName(Strings.nullToEmpty(downloadLink));
        File imageFile = new File(getImageStoreDir(), downloadLink.substring(downloadLink.lastIndexOf("/") + 1));
        log.debug("FileName: {}, DownloadLink: {}", fileName, downloadLink);
        if(!imageCache.containsKey(fileName)) {
            if(imageFile.exists()) {
                HttpHead headRequest = new HttpHead(downloadLink);
                headRequest.addHeader("Referer", PixivURL.getPixivRefererLink(illustId));
                HttpResponse headResponse;
                try {
                    headResponse = pixivDownload.getHttpClient().execute(headRequest);
                } catch (IOException e) {
                    log.error("获取图片大小失败！", e);
                    return "图片获取失败!";
                }
                String contentLengthStr = headResponse.getFirstHeader(HttpHeaderNames.CONTENT_LENGTH.toString()).getValue();
                log.debug("图片大小: {}B", contentLengthStr);
                if (imageFile.length() == Long.parseLong(contentLengthStr)) {
                    imageCache.put(URLs.getResourceName(downloadLink), imageFile);
                    log.debug("作品Id {} 第 {} 页缓存已补充.", illustId, pageIndex);
                    return getImageToBotCode(imageFile, false).toString();
                }
            }

            ImageCacheObject taskObject = new ImageCacheObject(imageCache, illustId, downloadLink, imageFile);
            try {
                imageCacheExecutor.executorSync(taskObject);
            } catch (InterruptedException e) {
                log.error("等待图片下载时发生中断", e);
                return "图片获取失败!";
            }
        } else {
            log.debug("图片 {} 缓存命中.", fileName);
        }

        return getImageToBotCode(imageCache.get(fileName), false).toString();
    }

    private static BotCode getImageToBotCode(File targetFile, boolean updateCache) {
        String fileName = targetFile.getName();
        BotCode code = BotCode.parse(CQCode.image(getImageStoreDir().getName() + "/" + fileName));
        code.addParameter("absolutePath", targetFile.getAbsolutePath());
        code.addParameter("imageName", fileName.substring(0, fileName.lastIndexOf(".")));
        code.addParameter("updateCache", updateCache ? "true" : "false");
        return code;
    }

    static void clearCache() {
        log.warn("正在清除所有缓存...");
        imageCache.clear();
        illustInfoCache.clear();
        illustPreLoadDataCache.clear();
        pagesCache.clear();
        searchBodyCache.clear();
        File imageStoreDir = new File(System.getProperty("cgj.cqRootDir") + "data/image/cgj/");
        File[] listFiles = imageStoreDir.listFiles();
        if (listFiles == null) {
            log.debug("图片缓存目录为空或内部文件获取失败!");
        } else {
            for (File file : listFiles) {
                log.debug("图片文件 {} 删除: {}", file.getName(), file.delete());
            }
        }
        log.debug("图片缓存目录删除: {}", imageStoreDir.delete());
        log.warn("缓存删除完成.");
    }

    /*
    下一目标：
    添加定时发图
    定时发图支持设置关注标签
    标签....标签支持搜索吧
     */

    private static boolean isNoSafe(int illustId, Properties settingProp, boolean returnRaw) throws IOException {
        boolean rawValue = getIllustInfo(illustId, false).getAsJsonArray("tags").contains(new JsonPrimitive("R-18"));
        return returnRaw || settingProp == null ? rawValue : rawValue && !settingProp.getProperty("image.allowR18", "false").equalsIgnoreCase("true");
    }

    private static JsonObject getIllustInfo(int illustId, boolean flushCache) throws IOException {
        String illustIdStr = buildSyncKey(Integer.toString(illustId));
        JsonObject illustInfoObj = null;
        if (!illustInfoCache.exists(illustIdStr) || flushCache) {
            synchronized (illustIdStr) { // TODO: 这里要不做成HashMap存储key而避免使用常量池?
                if (!illustInfoCache.exists(illustIdStr) || flushCache) {
                    illustInfoObj = pixivDownload.getIllustInfoByIllustId(illustId);
                    illustInfoCache.update(illustIdStr, illustInfoObj, null);
                }
            }
        }

        if(Objects.isNull(illustInfoObj)) {
            illustInfoObj = illustInfoCache.getCache(illustIdStr).getAsJsonObject();
            log.debug("作品Id {} IllustInfo缓存命中.", illustId);
        }
        return illustInfoObj;
    }

    /**
     * 获取作品预加载数据.
     * 可以获取作品的一些与用户相关的信息
     * @param illustId 作品Id
     * @param flushCache 是否刷新缓存
     * @return 成功返回JsonObject对象
     * @throws IOException 当Http请求处理发生异常时抛出
     */
    public static JsonObject getIllustPreLoadData(int illustId, boolean flushCache) throws IOException {
        String illustIdStr = buildSyncKey(Integer.toString(illustId));
        JsonObject result = null;
        if (!illustPreLoadDataCache.exists(illustIdStr) || flushCache) {
            synchronized (illustIdStr) {
                if (!illustPreLoadDataCache.exists(illustIdStr) || flushCache) {
                    log.info("IllustId {} 缓存失效, 正在更新...", illustId);
                    JsonObject preLoadDataObj = pixivDownload.getIllustPreLoadDataById(illustId)
                            .getAsJsonObject("illust")
                            .getAsJsonObject(Integer.toString(illustId));

                    long expire = 7200 * 1000;
                    String propValue = globalProp.getProperty("cache.illustPreLoadData.expire", "7200000");
                    log.debug("PreLoadData有效时间设定: {}", propValue);
                    try {
                        expire = Long.parseLong(propValue);
                    } catch (Exception e) {
                        log.warn("全局配置项 \"{}\" 值非法, 已使用默认值: {}", propValue, expire);
                    }

                    result = preLoadDataObj;
                    illustPreLoadDataCache.update(illustIdStr, preLoadDataObj, expire);
                    log.info("作品Id {} preLoadData缓存已更新(有效时间: {})", illustId, expire);
                }
            }
        }

        if(Objects.isNull(result)) {
            result = illustPreLoadDataCache.getCache(illustIdStr).getAsJsonObject();
            log.debug("作品Id {} PreLoadData缓存命中.", illustId);
        }
        return result;
    }

    public static List<String> getIllustPages(int illustId, PixivDownload.PageQuality quality, boolean flushCache) throws IOException {
        String pagesSign = buildSyncKey(Integer.toString(illustId), ".", quality.name());
        List<String> result = null;
        if (!pagesCache.exists(pagesSign) || flushCache) {
            synchronized (pagesSign) {
                if (!pagesCache.exists(pagesSign) || flushCache) {
                    List<String> linkList = PixivDownload.getIllustAllPageDownload(pixivDownload.getHttpClient(), pixivDownload.getCookieStore(), illustId, quality);
                    result = linkList;
                    pagesCache.update(pagesSign, linkList, null);
                }
            }
        }

        if(Objects.isNull(result)) {
            result = pagesCache.getCache(pagesSign);
            log.debug("作品Id {} Pages缓存命中.", illustId);
        }
        return result;
    }
    
    private static File getImageStoreDir() {
        if(!imageStoreDir.exists() && !imageStoreDir.mkdirs()) {
            log.warn("酷Q图片缓存目录失效！(Path: {} )", imageStoreDir.getAbsolutePath());
            throw new RuntimeException(new IOException("文件夹创建失败!"));
        }
        return imageStoreDir;
    }


    private final static Random expireTimeFloatRandom = new Random();
    /**
     * 获取排行榜
     * @param contentType 排行榜类型
     * @param mode 排行榜模式
     * @param queryDate 查询时间
     * @param start 开始排名, 从1开始
     * @param range 取范围
     * @param flushCache 是否强制刷新缓存
     * @return 成功返回有值List, 失败且无异常返回空
     * @throws IOException 获取异常时抛出
     */
    public static List<JsonObject> getRankingInfoByCache(PixivURL.RankingContentType contentType, PixivURL.RankingMode mode, Date queryDate, int start, int range, boolean flushCache) throws IOException {
        if(!contentType.isSupportedMode(mode)) {
            log.warn("试图获取不支持的排行榜类型已拒绝.(ContentType: {}, RankingMode: {})", contentType.name(), mode.name());
            if(log.isDebugEnabled()) {
                try {
                    Thread.dumpStack();
                } catch(Exception e) {
                    log.debug("本次非法请求的堆栈信息如下: \n{}", Throwables.getStackTraceAsString(e));
                }
            }
            return new ArrayList<>(0);
        }

        String date = new SimpleDateFormat("yyyyMMdd").format(queryDate);
        String requestSign = buildSyncKey(contentType.name(), ".", mode.name(), ".", date);
        List<JsonObject> result = null;
        if(!rankingCache.exists(requestSign) || flushCache) {
            synchronized(requestSign) {
                if(!rankingCache.exists(requestSign) || flushCache) {
                    log.info("Ranking缓存失效, 正在更新...(RequestSign: {})", requestSign);
                    List<JsonObject> rankingResult = pixivDownload.getRanking(contentType, mode, queryDate, 1, 500);
                    if(rankingResult.size() == 0) {
                        log.info("数据获取失败, 将设置浮动有效时间以准备下次更新.");
                    }
                    result = new ArrayList<>(rankingResult).subList(start - 1, range);
                    rankingCache.update(requestSign, rankingResult,
                            rankingResult.size() == 0 ? 5400000 + expireTimeFloatRandom.nextInt(1800000) : 0);
                    log.info("Ranking缓存更新完成.(RequestSign: {})", requestSign);
                }
            }
        }

        if (Objects.isNull(result)) {
            result = rankingCache.getCache(requestSign, start - 1, range);
            log.debug("RequestSign [{}] 缓存命中.", requestSign);
        }
        log.debug("Result-Length: {}", result.size());
        return PixivDownload.getRanking(result, start - 1, range);
    }

    private static String buildSyncKey(String... keys) {
        StringBuilder sb = new StringBuilder();
        for (String string : keys) {
            sb.append(string);
        }
        return sb.toString().intern();
    }
}