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

package net.lamgc.cgj.bot.framework.message;

import net.lamgc.cgj.bot.framework.message.exception.UploadImageException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * @author LamGC
 */
public interface MessageSender {

    /**
     * 获取消息源类型.
     * @return 返回消息源类型, 不允许返回 null.
     */
    MessageSource getSource();

    /**
     * 获取消息源 Id.
     * @return 返回消息源 Id, 同一种消息源中不允许有两个相同的 Id.
     */
    long getId();

    /**
     * 发送消息
     * @param message 消息内容, 特殊内容将以功能码形式插入内容中.
     * @return 如果成功返回 0 或消息 Id, 发送失败返回负数代表错误码.
     */
    int sendMessage(String message);

    /**
     * 获取消息标识, 用于回复/撤回功能
     * @param msgId 消息Id, 通过 {@link #sendMessage(String)} 发送消息获得, 或从 MessageEvent 中获得.
     * @return 如果成功获取, 返回非null值, 如果不存在或无法获取, 返回 null.
     */
    String getMessageIdentify(int msgId);

    /**
     * 获取图片Url
     * @param imageIdentify 图片标识
     * @return 返回图片Url
     */
    String getImageUrl(String imageIdentify);

    /**
     * 获取图片输入流
     * @param imageIdentify 图片标识
     * @return 返回图片输入流.
     * @throws IOException 当输入流获取发生异常时可抛出.
     */
    InputStream getImageAsInputStream(String imageIdentify) throws IOException;

    /**
     * 上传图片.
     * @param imageInput 图片输入流
     * @return 返回图片的标识, 如果平台无提供相关标识, 可能需要框架内部处理;
     *         返回的标识将会在需要发送时, 以<pre>[Platform:image,id=图片标识]</pre>的形式进行指示;
     *         标识会在内部加入平台标识, 除非平台自带, 否则无需自行添加.
     * @throws UploadImageException 如果图片上传时发生异常可抛出.
     */
    String uploadImage(InputStream imageInput) throws UploadImageException;

    /**
     * 上传图片.
     * @param imageFile 图片文件
     * @return 返回图片的标识, 如果平台无提供相关标识, 可能需要框架内部处理.
     * @throws UploadImageException 如果图片上传时发生异常可抛出.
     */
    default String uploadImage(File imageFile) throws UploadImageException {
        try (InputStream imageInput = new FileInputStream(imageFile)) {
            return uploadImage(imageInput);
        } catch(Exception e) {
            throw new UploadImageException("Image upload exception", e);
        }
    }

}