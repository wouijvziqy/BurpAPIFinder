package burp;

import burp.ui.ConfigPanel;
import burp.dataModel.ApiDataModel;
import burp.util.*;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author： shaun
 * @create： 2024/4/5 09:07
 * @description：TODO
 */
public class IProxyScanner implements IProxyListener {
    private static UrlScanCount haveScanUrl = new UrlScanCount();
    public static int totalScanCount = 0;
    final ThreadPoolExecutor executorService;  // 修改这行
    private static IExtensionHelpers helpers;

    public IProxyScanner() {
        helpers = BurpExtender.getHelpers();
        // 先新建一个进程用于后续处理任务
        int coreCount = Runtime.getRuntime().availableProcessors();
        coreCount = Math.max(coreCount, 20);
        int maxPoolSize = coreCount * 2;
        BurpExtender.getStdout().println("[+] Number of threads enabled:: " + maxPoolSize);
        long keepAliveTime = 60L;
        executorService = new ThreadPoolExecutor(
                coreCount,
                maxPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), // 可以根据需要调整队列类型和大小
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy() // 当线程池和队列都满时，任务在调用者线程中执行
        );
    }

    public static void setHaveScanUrlNew(){
        haveScanUrl = new UrlScanCount();
        ConfigPanel.lbSuccessCount.setText("0");
        ConfigPanel.lbRequestCount.setText("0");
        BurpExtender.getDataBaseService().clearApiDataTable();
        BurpExtender.getDataBaseService().clearPathDataTable();
        BurpExtender.getDataBaseService().clearRequestsResponseTable();
    }

    public void processProxyMessage(boolean messageIsRequest, final IInterceptedProxyMessage iInterceptedProxyMessage) {
        if (!messageIsRequest) {
            totalScanCount += 1;
            ConfigPanel.lbSuccessCount.setText(String.valueOf(BurpExtender.getDataBaseService().getApiDataCount()));
            ConfigPanel.lbRequestCount.setText(Integer.toString(totalScanCount));

            IHttpRequestResponse requestResponse = iInterceptedProxyMessage.getMessageInfo();
            final IHttpRequestResponse resrsp = iInterceptedProxyMessage.getMessageInfo();
            String method = helpers.analyzeRequest(resrsp).getMethod();
            // 提取url，过滤掉静态文件
            String url = String.valueOf(helpers.analyzeRequest(resrsp).getUrl());
            byte[] responseBytes = resrsp.getResponse();

            // 返回结果为空则退出
            if (responseBytes == null || responseBytes.length == 0) {
                BurpExtender.getStdout().println("返回结果为空: " + url);
                return;
            }
            String statusCode = String.valueOf(BurpExtender.getCallbacks().getHelpers().analyzeResponse(responseBytes).getStatusCode());
            String extractBaseUrl = Utils.extractBaseUrl(url);
            if (extractBaseUrl.equals("-")){
                return;
            }
            if (ConfigPanel.toggleButton.isSelected()) {
            } else if (haveScanUrl.get((Utils.extractBaseUrl(url).hashCode() + statusCode)) <= 0) {
                haveScanUrl.add(Utils.extractBaseUrl(url).hashCode() + statusCode);
            } else {
                BurpExtender.getStdout().println("[-] 已识别过URL，不进行重复识别： " + url);
                return;
            }
            if (Utils.isStaticFile(url) && !url.contains("favicon.") && !url.contains(".ico")){
                BurpExtender.getStdout().println("[+]静态文件，不进行url识别：" + url);
                return;
            }

            // 网页提取URL并进行指纹识别
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    Map<String, Object> pathData = new HashMap<>();
                    // 当前请求的URL，requests，Response，以及findUrl来区别是否为提取出来的URL
                    ApiDataModel originalApiData = new ApiDataModel(
                            Constants.TREE_STATUS_COLLAPSE,
                            String.valueOf(iInterceptedProxyMessage.getMessageReference()),
                            Utils.getUriFromUrl(url),
                            "0",
                            false,
                            "-",
                            BurpExtender.getDataBaseService().insertOrUpdateRequestResponse(Utils.getUriFromUrl(url), requestResponse.getRequest(), requestResponse.getResponse()),
                            requestResponse.getHttpService(),
                            new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date()),
                            "-",
                            "-",
                            "-",
                            "-",
                            "\r\n");
                    try {
                        if (!url.contains("favicon.") && !url.contains(".ico")) {
                            String mime = helpers.analyzeResponse(responseBytes).getInferredMimeType();
                            URL urlUrl = helpers.analyzeRequest(resrsp).getUrl();

                            if (!pathData.containsKey(Utils.getPathFromUrl(url)) && !Utils.isStaticFile(url) && !Utils.isStaticPath(url) && !Utils.getPathFromUrl(url).endsWith(".js")) {
                                Map<String, Object> getUriData = new HashMap<String, Object>();
                                getUriData.put("requests", Base64.getEncoder().encodeToString(requestResponse.getRequest()));
                                getUriData.put("response", Base64.getEncoder().encodeToString(requestResponse.getResponse()));
                                getUriData.put("host", requestResponse.getHttpService().getHost());
                                getUriData.put("port", requestResponse.getHttpService().getPort());
                                getUriData.put("protocol", requestResponse.getHttpService().getProtocol());
                                getUriData.put("isJsFindUrl", "N");
                                getUriData.put("method", method);
                                getUriData.put("status", statusCode);
                                getUriData.put("isImportant", false);
                                getUriData.put("result", "-");
                                getUriData.put("result info", "-");
                                getUriData.put("describe", "-");
                                getUriData.put("time", new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(new Date()));
                                pathData.put(Utils.getPathFromUrl(url), getUriData);
                            }

                            if (!ConfigPanel.toggleButton.isSelected()) {
                                // 针对html页面提取
                                Set<String> urlSet = new HashSet<>(Utils.extractUrlsFromHtml(url, new String(responseBytes)));
                                // 针对JS页面提取
                                if (mime.equals("script") || mime.equals("HTML") || url.contains(".htm") || Utils.isGetUrlExt(url)) {
                                    urlSet.addAll(Utils.findUrl(urlUrl, new String(responseBytes)));
                                }
                                // 依次遍历urlSet获取其返回的response值
                                for (String getUrl : urlSet) {
                                    if (Utils.isGetUrlExt(getUrl) || Utils.getPathFromUrl(getUrl).length() < 4) {
                                        BurpExtender.getStdout().println("白Ext或者太短path，过滤掉： " + getUrl);
                                        continue;
                                    }
                                    pathData.put(Utils.getPathFromUrl(getUrl), HTTPUtils.makeGetRequest(getUrl));
                                }
                            }

                            if (pathData.isEmpty()) {
                                return;
                            }

                        }
                    }catch (Exception e) {
                        BurpExtender.getStderr().println("数据提取uri的时候报错：" + url);
                        e.printStackTrace(BurpExtender.getStderr());
                    }

                    try{
                        ApiDataModel newOriginalApiData = FingerUtils.FingerFilter(url, originalApiData, pathData, BurpExtender.getHelpers());
                        if (!BurpExtender.getDataBaseService().isExistApiDataModelByUri(Utils.getUriFromUrl(url))) {
                            newOriginalApiData.setHavingImportant(BurpExtender.getDataBaseService().hasImportantPathDataByUrl(Utils.getUriFromUrl(url)));
                            BurpExtender.getDataBaseService().insertApiDataModel(newOriginalApiData);
                        } else {
                            ApiDataModel existedApiData = BurpExtender.getDataBaseService().selectApiDataModelByUri(Utils.getUriFromUrl(url));
                            ApiDataModel mergeApiData = mergeApiData(url, existedApiData, newOriginalApiData);
                            mergeApiData.setHavingImportant(BurpExtender.getDataBaseService().hasImportantPathDataByUrl(Utils.getUriFromUrl(url)));
                            BurpExtender.getDataBaseService().updateApiDataModelByUrl(mergeApiData);
                        }
                    } catch (Exception e) {
                        BurpExtender.getStderr().println("数据合并的时候报错： " + url);
                        e.printStackTrace(BurpExtender.getStderr());
                    }
                }
            });
        }

    }

    public static ApiDataModel mergeApiData(String url, ApiDataModel apiDataModel1, ApiDataModel apiDataModel2){

        // 合并status
        // 将字符串分割成数组
        String[] apiDataStatusList1 = apiDataModel1.getStatus().split(",");
        String[] apiDataStatusList2 = apiDataModel2.getStatus().split(",");
        // 创建一个 HashSet 并添加所有元素来去除重复项
        Set<String> statusSet = new HashSet<>();
        statusSet.addAll(Arrays.asList(apiDataStatusList1));
        statusSet.addAll(Arrays.asList(apiDataStatusList2));
        // 将 Set 转换回 String，元素之间用逗号分隔
        apiDataModel1.setStatus(String.join(",", statusSet).replace("-,", "").replace(",-", ""));

        // 合并result
        // 将字符串分割成数组
        String[] apiDataResultList1 = apiDataModel1.getResult().split(",");
        String[] apiDataResultList2 = apiDataModel2.getResult().split(",");
        // 创建一个 HashSet 并添加所有元素来去除重复项
        Set<String> resultSet = new HashSet<>();
        resultSet.addAll(Arrays.asList(apiDataResultList1));
        resultSet.addAll(Arrays.asList(apiDataResultList2));
        // 将 Set 转换回 String，元素之间用逗号分隔
        apiDataModel1.setResult(String.join(",", resultSet).replace("-,", "").replace(",-", "").replace(",误报", "").replace("误报,", ""));

        // 合并describe
        String[] apiDataDescribeList1 = apiDataModel1.getDescribe().split(",");
        String[] apiDataDescribeList2 = apiDataModel2.getDescribe().split(",");
        // 创建一个 HashSet 并添加所有元素来去除重复项
        Set<String> describeSet = new HashSet<>();
        describeSet.addAll(Arrays.asList(apiDataDescribeList1));
        describeSet.addAll(Arrays.asList(apiDataDescribeList2));
        // 将 Set 转换回 String，元素之间用逗号分隔
        apiDataModel1.setDescribe(String.join(",", describeSet).replace("-,", "").replace(",-", "").replace(",误报", "").replace("误报,", ""));

        // 合并PathData
        apiDataModel1.setTime(apiDataModel2.getTime());

        apiDataModel1.setPathNumber(BurpExtender.getDataBaseService().getPathDataCountByUrl(Utils.getUriFromUrl(url)));
        apiDataModel1.setResultInfo((apiDataModel1.getResultInfo() + "\r\n" + apiDataModel2.getResultInfo()).strip());
        return apiDataModel1;
    }

}
