package org.example;

import com.google.gson.Gson;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

// from: https://github.com/soot-oss/soot/blob/3966f565db6dc2882c3538ffc39e44f4c14b5bcf/src/main/java/soot/util/EscapedWriter.java
/*-
 * #%L
 * Soot - a J*va Optimization Framework
 * %%
 * Copyright (C) 1997 - 1999 Raja Vallee-Rai
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

/**
 * A FilterWriter which catches to-be-escaped characters (<code>\\unnnn</code>) in the input and substitutes their escaped
 * representation. Used for Soot output.
 */
class EscapedWriter extends FilterWriter {
    /** Convenience field containing the system's line separator. */
    public final String lineSeparator = System.getProperty("line.separator");
    private final int cr = lineSeparator.charAt(0);
    private final int lf = (lineSeparator.length() == 2) ? lineSeparator.charAt(1) : -1;

    /** Constructs an EscapedWriter around the given Writer. */
    public EscapedWriter(Writer fos) {
        super(fos);
    }

    private final StringBuffer mini = new StringBuffer();

    /** Print a single character (unsupported). */
    public void print(int ch) throws IOException {
        write(ch);
        throw new RuntimeException();
    }

    /** Write a segment of the given String. */
    public void write(String s, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(s.charAt(i));
        }
    }

    /** Write a single character. */
    public void write(int ch) throws IOException {
        if (ch >= 32 && ch <= 126 || ch == cr || ch == lf || ch == ' ') {
            super.write(ch);
            return;
        }

        mini.setLength(0);
        mini.append(Integer.toHexString(ch));

        while (mini.length() < 4) {
            mini.insert(0, "0");
        }

        mini.insert(0, "\\u");
        for (int i = 0; i < mini.length(); i++) {
            super.write(mini.charAt(i));
        }
    }
}

enum OcrMode {
    LOCAL_PROCESS,  // 本地进程
    SOCKET_SERVER   // 套接字服务器
}

class OcrCode {
    public static final int OK = 100;
    public static final int NO_TEXT = 101;
}

class OcrEntry {
    String text;
    int[][] box;
    double score;

    @Override
    public String toString() {
        return "RecognizedText{" +
                "text='" + text + '\'' +
                ", box=" + Arrays.toString(box) +
                ", score=" + score +
                '}';
    }
}

class OcrResponse {
    int code;
    OcrEntry[] data;
    String msg;
    String hotUpdate;

    @Override
    public String toString() {
        return "OcrResponse{" +
                "code=" + code +
                ", data=" + Arrays.toString(data) +
                ", msg='" + msg + '\'' +
                ", hotUpdate='" + hotUpdate + '\'' +
                '}';
    }

    public OcrResponse() {
    }

    public OcrResponse(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}

public class Ocr implements AutoCloseable {
    // 公共
    Gson gson;
    boolean ocrReady = false;
    Map<String, Object> arguments;
    BufferedReader reader;
    BufferedWriter writer;
    OcrMode mode;
    private volatile boolean closed = false;  // 标记是否已关闭

    // 本地进程模式
    Process process;
    File exePath;

    // 套接字服务器模式
    String serverAddr;
    int serverPort;
    Socket clientSocket;
    boolean isLoopback = false;

    /**
     * 使用套接字模式初始化
     * @param serverAddr 服务器地址
     * @param serverPort 服务器端口
     * @param arguments 参数
     * @throws IOException IO异常
     */
    public Ocr(String serverAddr, int serverPort, Map<String, Object> arguments) throws IOException {
        this.mode = OcrMode.SOCKET_SERVER;
        this.arguments = arguments;
        this.serverAddr = serverAddr;
        this.serverPort = serverPort;
        checkIfLoopback();
        initOcr();
        registerShutdownHook();
    }

    /**
     * 使用本地进程模式初始化
     * @param exePath 可执行文件路径
     * @param arguments 参数
     * @throws IOException IO异常
     */
    public Ocr(File exePath, Map<String, Object> arguments) throws IOException {
        this.mode = OcrMode.LOCAL_PROCESS;
        this.arguments = arguments;
        this.exePath = exePath;
        initOcr();
        registerShutdownHook();
    }

    /**
     * 注册JVM关闭钩子，确保程序退出时资源被清理
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!closed) {
                System.out.println("JVM 关闭中，正在清理 OCR 资源...");
                forceClose();
            }
        }));
    }

    /**
     * 强制关闭，不等待进程正常退出
     */
    private void forceClose() {
        if (closed) return;
        closed = true;

        switch (this.mode) {
            case LOCAL_PROCESS:
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    System.out.println("已强制终止 OCR 进程");
                }
                // 关闭流
                closeStreams();
                break;
            case SOCKET_SERVER:
                closeSocket();
                break;
        }
    }

    /**
     * 关闭输入输出流
     */
    private void closeStreams() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            // 忽略关闭异常
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            // 忽略关闭异常
        }
    }

    /**
     * 关闭套接字连接
     */
    private void closeSocket() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            // 忽略
        }
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException e) {
            // 忽略
        }
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            // 忽略
        }
    }

    private void initOcr() throws IOException {
        gson = new Gson();

        List<String> commandList = new ArrayList<>();
        if (arguments != null) {
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                commandList.add("--" + entry.getKey() + "=" + entry.getValue().toString());
            }
        }

        for (String c : commandList) {
            if (!StandardCharsets.US_ASCII.newEncoder().canEncode(c)) {
                throw new IllegalArgumentException("参数不能含有非 ASCII 字符");
            }
        }

        System.out.println("当前参数：" + (commandList.isEmpty() ? "空" : commandList));

        switch (this.mode) {
            case LOCAL_PROCESS: {
                File workingDir = exePath.getParentFile();
                if (isLinux()) {
                    // Linux 下解压后的默认布局是 ../bin/exe，需要再往上一层
                    workingDir = workingDir.getParentFile();
                }
                commandList.add(0, exePath.toString());
                ProcessBuilder pb = new ProcessBuilder(commandList);
                pb.directory(workingDir);
                pb.redirectErrorStream(true);

                if (isLinux()) {
                    // Linux 下启动，需要设置 LD_LIBRARY_PATH
                    File libLocation = new File(workingDir, "lib");
                    pb.environment().put("LD_LIBRARY_PATH", libLocation.getAbsolutePath());
                }

                process = pb.start();

                InputStream stdout = process.getInputStream();
                OutputStream stdin = process.getOutputStream();
                reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));

                ocrReady = false;
                String line;
                int retryCount = 0;
                int maxRetries = 50; // 最多等待5秒（每次100ms）

                while (!ocrReady && retryCount < maxRetries) {
                    if (reader.ready()) {
                        line = reader.readLine();
                        if (line != null) {
                            if (isLinux() && line.contains("not found (required by")) {
                                System.out.println("可能存在依赖库问题：" + line);
                                break;
                            }
                            if (line.contains("OCR init completed")) {
                                ocrReady = true;
                                break;
                            }
                            System.out.println("OCR输出: " + line);
                        }
                    } else {
                        // 等待100ms再检查
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        retryCount++;
                    }
                }

                if (ocrReady) {
                    System.out.println("初始化OCR成功");
                } else {
                    System.out.println("初始化OCR失败，请检查输出");
                    if (!process.isAlive()) {
                        throw new IOException("OCR进程意外退出");
                    }
                }
                break;
            }
            case SOCKET_SERVER: {
                clientSocket = new Socket(serverAddr, serverPort);
                clientSocket.setKeepAlive(true);
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
                ocrReady = true;
                System.out.println("已连接到OCR套接字服务器，假设服务器已初始化成功");
                break;
            }
        }
    }

    /**
     * 使用图片路径进行 OCR
     * @param imgFile 图片文件
     * @return OCR响应
     * @throws IOException IO异常
     */
    public OcrResponse runOcr(File imgFile) throws IOException {
        if (closed) {
            throw new IllegalStateException("OCR 实例已关闭");
        }
        if (mode == OcrMode.SOCKET_SERVER && !isLoopback) {
            System.out.println("套接字模式下服务器不在本地，发送路径可能失败");
        }
        Map<String, String> reqJson = new HashMap<>();
        reqJson.put("image_path", imgFile.toPath().toAbsolutePath().toString());
        return this.sendJsonToOcr(reqJson);
    }

    /**
     * 使用剪贴板中图片进行 OCR
     * @return OCR响应
     * @throws IOException IO异常
     */
    public OcrResponse runOcrOnClipboard() throws IOException {
        if (closed) {
            throw new IllegalStateException("OCR 实例已关闭");
        }
        if (mode == OcrMode.SOCKET_SERVER && !isLoopback) {
            System.out.println("套接字模式下服务器不在本地，发送剪贴板可能失败");
        }
        Map<String, String> reqJson = new HashMap<>();
        reqJson.put("image_path", "clipboard");
        return this.sendJsonToOcr(reqJson);
    }

    /**
     * 使用 Base64 编码的图片进行 OCR
     * @param base64str Base64字符串
     * @return OCR响应
     * @throws IOException IO异常
     */
    public OcrResponse runOcrOnImgBase64(String base64str) throws IOException {
        if (closed) {
            throw new IllegalStateException("OCR 实例已关闭");
        }
        Map<String, String> reqJson = new HashMap<>();
        reqJson.put("image_base64", base64str);
        return this.sendJsonToOcr(reqJson);
    }

    /**
     * 使用图片 Byte 数组进行 OCR
     * @param fileBytes 图片字节数组
     * @return OCR响应
     * @throws IOException IO异常
     */
    public OcrResponse runOcrOnImgBytes(byte[] fileBytes) throws IOException {
        if (closed) {
            throw new IllegalStateException("OCR 实例已关闭");
        }
        return this.runOcrOnImgBase64(Base64.getEncoder().encodeToString(fileBytes));
    }

    private OcrResponse sendJsonToOcr(Map<String, String> reqJson) throws IOException {
        if (!isAlive()) {
            throw new RuntimeException("OCR进程已经退出或连接已断开");
        }

        StringWriter sw = new StringWriter();
        EscapedWriter ew = new EscapedWriter(sw);
        gson.toJson(reqJson, ew);

        // 重建 socket，修复长时间无请求时 socket 断开
        if (OcrMode.SOCKET_SERVER == mode) {
            reconnectSocket();
        }

        writer.write(sw.getBuffer().toString());
        writer.write("\r\n");
        writer.flush();

        String resp = reader.readLine();
        if (resp == null) {
            throw new IOException("OCR服务未返回响应");
        }

        Map<?, ?> rawJsonObj = gson.fromJson(resp, Map.class);
        if (rawJsonObj.get("data") instanceof String) {
            return new OcrResponse((int) Double.parseDouble(rawJsonObj.get("code").toString()),
                    rawJsonObj.get("data").toString());
        }

        return gson.fromJson(resp, OcrResponse.class);
    }

    /**
     * 重新连接套接字
     */
    private void reconnectSocket() throws IOException {
        try {
            writer.close();
        } catch (IOException e) {
            // 忽略
        }
        try {
            reader.close();
        } catch (IOException e) {
            // 忽略
        }
        try {
            clientSocket.close();
        } catch (IOException e) {
            // 忽略
        }

        clientSocket = new Socket(serverAddr, serverPort);
        clientSocket.setKeepAlive(true);
        reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private void checkIfLoopback() {
        if (this.mode != OcrMode.SOCKET_SERVER) return;
        try {
            InetAddress address = InetAddress.getByName(serverAddr);
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                this.isLoopback = true;
            } else {
                NetworkInterface networkInterface = NetworkInterface.getByInetAddress(address);
                this.isLoopback = networkInterface != null && networkInterface.isLoopback();
            }
        } catch (Exception e) {
            // 非关键路径
            System.out.println("套接字模式，未能确认服务端是否在本地: " + e.getMessage());
        }
        System.out.println("套接字模式下，服务端在本地：" + isLoopback);
    }

    private boolean isAlive() {
        switch (this.mode) {
            case LOCAL_PROCESS:
                return process != null && process.isAlive();
            case SOCKET_SERVER:
                return clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
        }
        return false;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }

    /**
     * 检查OCR是否就绪
     * @return 是否就绪
     */
    public boolean isReady() {
        return ocrReady && !closed;
    }

    /**
     * 关闭OCR实例，释放资源
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        System.out.println("正在关闭 OCR 实例...");

        switch (this.mode) {
            case LOCAL_PROCESS:
                closeLocalProcess();
                break;
            case SOCKET_SERVER:
                closeSocket();
                break;
        }

        System.out.println("OCR 实例已关闭");
    }

    /**
     * 关闭本地进程模式
     */
    private void closeLocalProcess() {
        if (process == null) return;

        if (process.isAlive()) {
            // 尝试正常关闭
            process.destroy();

            // 等待最多5秒正常退出
            try {
                boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                if (terminated) {
                    System.out.println("OCR 进程已正常退出");
                } else {
                    // 强制终止
                    process.destroyForcibly();
                    boolean forcedTerminated = process.waitFor(2, TimeUnit.SECONDS);
                    if (forcedTerminated) {
                        System.err.println("OCR 进程已被强制终止");
                    } else {
                        System.err.println("OCR 进程可能仍在运行");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                System.err.println("等待OCR进程退出时被中断，已强制终止");
            }
        }

        // 关闭流
        closeStreams();
    }
}