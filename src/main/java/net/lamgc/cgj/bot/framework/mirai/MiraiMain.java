package net.lamgc.cgj.bot.framework.mirai;

import net.lamgc.cgj.bot.boot.ApplicationBoot;
import net.lamgc.cgj.bot.boot.BotGlobal;
import net.lamgc.cgj.bot.event.BotEventHandler;
import net.lamgc.cgj.bot.framework.mirai.message.MiraiMessageEvent;
import net.lamgc.cgj.bot.framework.mirai.message.MiraiMessageSenderFactory;
import net.lamgc.cgj.bot.message.MessageSenderBuilder;
import net.lamgc.cgj.bot.util.GroupMuteManager;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.BotFactoryJvm;
import net.mamoe.mirai.event.events.BotMuteEvent;
import net.mamoe.mirai.event.events.BotUnmuteEvent;
import net.mamoe.mirai.japt.Events;
import net.mamoe.mirai.message.FriendMessageEvent;
import net.mamoe.mirai.message.GroupMessageEvent;
import net.mamoe.mirai.message.MessageEvent;
import net.mamoe.mirai.message.TempMessageEvent;
import net.mamoe.mirai.utils.BotConfiguration;
import net.mamoe.mirai.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Base64;
import java.util.Properties;

public class MiraiMain implements Closeable {

    private final Logger log = LoggerFactory.getLogger(MiraiMain.class);

    private Bot bot;

    private final Properties botProperties = new Properties();

    private final GroupMuteManager muteManager = new GroupMuteManager();

    public void init() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        try {
            Class.forName(BotEventHandler.class.getName());
        } catch (ClassNotFoundException e) {
            log.error("加载BotEventHandler时发生异常", e);
            return;
        }

        File botPropFile = new File(BotGlobal.getGlobal().getDataStoreDir(), "./bot.properties");
        try (Reader reader = new BufferedReader(new FileReader(botPropFile))) {
            botProperties.load(reader);
        } catch (IOException e) {
            log.error("机器人配置文件读取失败!", e);
            return;
        }

        Utils.setDefaultLogger(MiraiToSlf4jLoggerAdapter::new);
        BotConfiguration configuration = new BotConfiguration();
        configuration.randomDeviceInfo();
        configuration.setProtocol(BotConfiguration.MiraiProtocol.ANDROID_PAD);

        // 心跳包周期间隔 (ms)
        configuration.setHeartbeatPeriodMillis(
                Long.parseLong(botProperties.getProperty("network.heartbeatPeriodMillis", "60000")));
        // 心跳包超时时间 (ms)
        configuration.setHeartbeatTimeoutMillis(
                Long.parseLong(botProperties.getProperty("network.heartbeatTimeoutMillis", "5000")));
        // 重连间隔时间
        configuration.setReconnectPeriodMillis(
                Integer.parseInt(botProperties.getProperty("network.reconnectPeriodMillis", "5")));
        // 重连最大次数
        configuration.setReconnectionRetryTimes(
                Integer.parseInt(botProperties.getProperty("network.reconnectionRetryTimes", "10")));

        bot = BotFactoryJvm.newBot(Long.parseLong(botProperties.getProperty("bot.qq", "0")),
                Base64.getDecoder().decode(botProperties.getProperty("bot.password", "")), configuration);
        Events.subscribeAlways(GroupMessageEvent.class, this::executeMessageEvent);
        Events.subscribeAlways(FriendMessageEvent.class, this::executeMessageEvent);
        Events.subscribeAlways(TempMessageEvent.class, this::executeMessageEvent);
        Events.subscribeAlways(BotMuteEvent.class,
                event -> muteManager.setMuteState(event.getGroup().getId(), true));
        Events.subscribeAlways(BotUnmuteEvent.class,
                event -> muteManager.setMuteState(event.getGroup().getId(), false));
        bot.login();
        MessageSenderBuilder.setCurrentMessageSenderFactory(new MiraiMessageSenderFactory(bot));
        ApplicationBoot.initialBot();
        bot.join();
    }

    /**
     * 处理消息事件
     * @param message 消息事件对象
     */
    private void executeMessageEvent(MessageEvent message) {
        log.debug("Mirai Message: {}", message);
        if(message instanceof GroupMessageEvent) {
            GroupMessageEvent groupMessageEvent = (GroupMessageEvent) message;
            Boolean muteState = muteManager.isMute(groupMessageEvent.getGroup().getId(), true);
            if(muteState == null) {
                boolean mute = groupMessageEvent.getGroup().getBotMuteRemaining() != 0;
                muteManager.setMuteState(groupMessageEvent.getGroup().getId(), mute);
                if(mute) {
                    return;
                }
            } else if(muteState) {
                return;
            }
        }
        BotEventHandler.executeMessageEvent(MiraiMessageEvent.covertEventObject(message));
    }

    /**
     * 关闭机器人
     */
    public synchronized void close() {
        if(bot == null) {
            return;
        }
        log.warn("正在关闭机器人...");
        bot.close(null);
        bot = null;
        log.warn("机器人已关闭.");
    }

}
