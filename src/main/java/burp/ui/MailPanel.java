package burp.ui;

import burp.*;
import burp.dataModel.ApiDataModel;
import burp.ui.renderer.HavingImportantRenderer;
import burp.ui.renderer.IsJsFindUrlRenderer;
import burp.util.Constants;
import burp.util.Utils;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.*;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;

public class MailPanel extends JPanel implements IMessageEditorController {
    private String tagName;
    private JSplitPane mainSplitPane;
    private JSplitPane infoSplitPane;
    private static IMessageEditor requestTextEditor;
    private static IMessageEditor responseTextEditor;
    public static byte[] requestsData;
    public static byte[] responseData;
    public static IHttpService iHttpService;
    private JScrollPane upScrollPane;
    private ConfigPanel configPanel;
    public static ITextEditor resultDeViewer;
    private static DefaultTableModel model;
    public static JTable table;
    public static int selectRow = 0;
    public static Timer timer;
    public static String historySearchText = "";
    public static String historySearchType = null;
    public static LocalDateTime operationStartTime = LocalDateTime.now();

    public MailPanel(IBurpExtenderCallbacks callbacks, String name) {
        // 主分隔面板
        mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        infoSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        setLayout(new BorderLayout());
        tagName = name;

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BorderLayout());

        // 首行配置面板
        configPanel = new ConfigPanel();

        // 数据展示面板
        model = new DefaultTableModel(new Object[]{"#", "ID", "URl", "PATH Number", "Method", "status", "isJsFindUrl", "HavingImportant", "Result", "describe", "Time"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                // This will make all cells of the table non-editable
                return false;
            }
        };
        table = new JTable(model){
            // 重写getToolTipText方法以返回特定单元格的数据
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                int col = columnAtPoint(e.getPoint());
                if (row > -1 && col > -1) {
                    Object value = getValueAt(row, col);
                    return value == null ? null : value.toString();
                }
                return super.getToolTipText(e);
            }
        };;
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 创建右键菜单
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem setUnImportantItem = new JMenuItem("误报");
        popupMenu.add(setUnImportantItem);
        // 将右键菜单添加到表格
        table.setComponentPopupMenu(popupMenu);

        setUnImportantItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Get the selected row from the table

                int selectedRow = table.getSelectedRow();
                if (selectedRow != -1) {
                    String listStatus = (String) table.getModel().getValueAt(selectedRow, 0);
                    String path = (String) table.getModel().getValueAt(selectedRow, 2);
                    try {
                        if (listStatus.equals(Constants.TREE_STATUS_COLLAPSE) || listStatus.equals(Constants.TREE_STATUS_EXPAND)) {
                            // Update the database
                            String url = (String) model.getValueAt(selectedRow, 2); // Assuming URL is in column 2
                            ApiDataModel apiDataModel = BurpExtender.getDataBaseService().selectApiDataModelByUri(url);
                            if (apiDataModel != null) {
                                apiDataModel.setHavingImportant(false);
                                apiDataModel.setResult("误报");
                                apiDataModel.setDescribe("误报");
                                BurpExtender.getDataBaseService().updateApiDataModelByUrl(apiDataModel);
                                BurpExtender.getDataBaseService().updateIsImportantToFalse(apiDataModel.getUrl());
                            }
                        } else {
                            String url = findUrlFromPath(selectedRow);
                            ApiDataModel apiDataModel = BurpExtender.getDataBaseService().selectApiDataModelByUri(url);
                            Map<String, Object> matchPathData = BurpExtender.getDataBaseService().selectPathDataByUrlAndPath(url, path);
                            matchPathData.put("result", "误报");
                            matchPathData.put("describe", "误报");
                            matchPathData.put("isImportant", false);
                            BurpExtender.getDataBaseService().insertOrUpdatePathData(url, path, false, (String) matchPathData.get("status"), "误报", matchPathData);
                            if (!BurpExtender.getDataBaseService().hasImportantPathDataByUrl(url)){
                                apiDataModel.setHavingImportant(false);
                                apiDataModel.setResult("误报");
                                apiDataModel.setDescribe("误报");
                                BurpExtender.getDataBaseService().updateApiDataModelByUrl(apiDataModel);
                            }

                        }
                        // 触发显示所有行事件
                        String searchText = "";
                        if (!ConfigPanel.searchField.getText().isEmpty()){
                            searchText = ConfigPanel.searchField.getText();
                        }
                        // 设置所有状态码为关闭
                        String selectedOption = (String)ConfigPanel.choicesComboBox.getSelectedItem();
                        MailPanel.showFilter(selectedOption, searchText);

                    }catch (Exception ek) {
                        BurpExtender.getStderr().println("[-] chick setUnImportantItem error : " + path);
                        ek.printStackTrace(BurpExtender.getStderr());
                    }

                }
            }
        });

        upScrollPane = new JScrollPane(table);
        // 将upScrollPane作为mainSplitPane的上半部分
        mainSplitPane.setTopComponent(upScrollPane);

        // 前两列设置宽度 30px、60px
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(1).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setMinWidth(300);
        table.getColumnModel().getColumn(7).setMinWidth(60);
        table.getColumnModel().getColumn(8).setMinWidth(150);
        table.getColumnModel().getColumn(10).setMinWidth(180);

        // 创建一个居中对齐的单元格渲染器
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);

        DefaultTableCellRenderer leftRenderer = new DefaultTableCellRenderer();
        leftRenderer.setHorizontalAlignment(JLabel.LEFT);

        table.getColumnModel().getColumn(0).setCellRenderer(leftRenderer);
        table.getColumnModel().getColumn(1).setCellRenderer(leftRenderer);
        table.getColumnModel().getColumn(2).setCellRenderer(leftRenderer);
        table.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(4).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(5).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(6).setCellRenderer(centerRenderer);
        table.getColumnModel().getColumn(7).setCellRenderer(leftRenderer);
        table.getColumnModel().getColumn(8).setCellRenderer(leftRenderer);
        table.getColumnModel().getColumn(9).setCellRenderer(leftRenderer);
        table.getColumnModel().getColumn(10).setCellRenderer(leftRenderer);

        IsJsFindUrlRenderer isJsFindUrlRenderer = new IsJsFindUrlRenderer();
        table.getColumnModel().getColumn(6).setCellRenderer(isJsFindUrlRenderer);
        HavingImportantRenderer havingImportantRenderer = new HavingImportantRenderer();
        table.getColumnModel().getColumn(7).setCellRenderer(havingImportantRenderer);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        try {
                            int row = table.rowAtPoint(e.getPoint());
                            if (row >= 0) {
                                selectRow = row;
                                String listStatus = (String) table.getModel().getValueAt(row, 0);
                                String url;
                                if (listStatus.equals(Constants.TREE_STATUS_COLLAPSE) || listStatus.equals(Constants.TREE_STATUS_EXPAND)) {
                                    url = (String) table.getModel().getValueAt(row, 2);
                                    ApiDataModel apiDataModel = BurpExtender.getDataBaseService().selectApiDataModelByUri(url);
                                    requestsData = BurpExtender.getDataBaseService().selectRequestResponseById(apiDataModel.getRequestsResponseIndex()).get("request");
                                    responseData = BurpExtender.getDataBaseService().selectRequestResponseById(apiDataModel.getRequestsResponseIndex()).get("response");
                                    iHttpService = apiDataModel.getiHttpService();
                                    requestTextEditor.setMessage(requestsData, true);
                                    responseTextEditor.setMessage(responseData, false);
                                    resultDeViewer.setText((apiDataModel.getResultInfo()).getBytes());
                                    if (apiDataModel.getListStatus().equals(Constants.TREE_STATUS_COLLAPSE)) {
                                        BurpExtender.getDataBaseService().updateListStatusByUrl(url, Constants.TREE_STATUS_EXPAND);
                                        modelExpand(apiDataModel, row);
                                    } else if (apiDataModel.getListStatus().equals(Constants.TREE_STATUS_EXPAND)) {
                                        BurpExtender.getDataBaseService().updateListStatusByUrl(url, Constants.TREE_STATUS_COLLAPSE);
                                        modeCollapse(apiDataModel, row);
                                    }
                                } else {
                                    try {
                                        String path = (String) table.getModel().getValueAt(row, 2);
                                        url = findUrlFromPath(row);
                                        ApiDataModel apiDataModel = BurpExtender.getDataBaseService().selectApiDataModelByUri(url);
                                        ;
                                        Map<String, Object> matchPathData = BurpExtender.getDataBaseService().selectPathDataByUrlAndPath(apiDataModel.getUrl(), path);
                                        requestsData = Base64.getDecoder().decode((String) matchPathData.get("requests"));
                                        responseData = Base64.getDecoder().decode((String) matchPathData.get("response"));
                                        iHttpService = Utils.iHttpService((String) matchPathData.get("host"), ((Double) matchPathData.get("port")).intValue(), (String) matchPathData.get("protocol"));
                                        requestTextEditor.setMessage(requestsData, true);
                                        responseTextEditor.setMessage(responseData, false);
                                        resultDeViewer.setText(((String) matchPathData.get("result info")).getBytes());
                                    } catch (Exception e) {
                                        e.printStackTrace(BurpExtender.getStderr());
                                    }

                                }
                            }
                        }catch (Exception ef) {
                            BurpExtender.getStderr().println("[-] Error click table: " + table.rowAtPoint(e.getPoint()));
                            ef.printStackTrace(BurpExtender.getStderr());
                        }
                    }
                });

            }
        });

        // 请求的面板
        requestTextEditor = callbacks.createMessageEditor(this, false);

        // 响应的面板
        responseTextEditor = callbacks.createMessageEditor(this, false);

        // 详细结果面板
        resultDeViewer = BurpExtender.getCallbacks().createTextEditor();

        toolbar.add(configPanel, BorderLayout.NORTH);
        toolbar.add(mainSplitPane, BorderLayout.CENTER);
        // 创建一个新的垂直 JSplitPane
        JSplitPane verticalSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplitPane.setOneTouchExpandable(true); // 允许有一个快速展开/折叠的按钮
        verticalSplitPane.setResizeWeight(0.5); // 设置初始分割比例
        // 将 toolbar 和 infoSplitPane 添加到新的 JSplitPane
        verticalSplitPane.setTopComponent(toolbar);
        verticalSplitPane.setBottomComponent(infoSplitPane);
        // 将 verticalSplitPane 添加到窗口的中心区域
        add(verticalSplitPane, BorderLayout.CENTER);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Result Info", resultDeViewer.getComponent());
        tabs.addTab("Original Response", responseTextEditor.getComponent());
        tabs.addTab("Request", requestTextEditor.getComponent());
        infoSplitPane.setBottomComponent(tabs);

        // 构建一个定时刷新页面函数
        // 创建一个每5秒触发一次的定时器
        int delay = 5000; // 延迟时间，单位为毫秒
        timer = new Timer(delay, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // 调用刷新表格的方法
                try{
                    refreshTableModel();
                } catch (Exception ep){
                    BurpExtender.getStderr().println("[!] 刷新表格报错， 报错如下：");
                    ep.printStackTrace(BurpExtender.getStderr());
                }
            }
        });

        // 启动定时器
        timer.start();
    }

    public static void refreshTableModel(){
        // 刷新页面, 如果自动更新关闭，则不刷新页面内容
        ConfigPanel.lbSuccessCount.setText(String.valueOf(BurpExtender.getDataBaseService().getApiDataCount()));
        if(ConfigPanel.getFlashButtonStatus()){
            if (Duration.between(operationStartTime, LocalDateTime.now()).getSeconds() > 600){
                ConfigPanel.setFlashButtonTrue();
            }
            return;
        }
        // 触发显示所有行事件
        String searchText = "";
        if (!ConfigPanel.searchField.getText().isEmpty()){
            searchText = ConfigPanel.searchField.getText();
        }
        // 设置所有状态码为关闭
        String selectedOption = (String)ConfigPanel.choicesComboBox.getSelectedItem();
        MailPanel.showFilter(selectedOption, searchText);
    }

    @Override
    public byte[] getRequest() {
        return requestsData;
    }

    @Override
    public byte[] getResponse() {
        return responseData;
    }

    @Override
    public IHttpService getHttpService() {
        return iHttpService;
    }

    public static void showFilter(String selectOption, String searchText){
        synchronized (model) {
            // 清空model后，根据URL来做匹配
            model.setRowCount(0);
            BurpExtender.getDataBaseService().updateListStatus(Constants.TREE_STATUS_COLLAPSE);
            // 判断当前历史记录是否为空
            if((selectOption.equals("全部"))){
                historySearchText = searchText;
            }

            // 获取数据库中的所有ApiDataModels
            List<ApiDataModel> allApiDataModels = BurpExtender.getDataBaseService().getAllApiDataModels();

            // 遍历apiDataModelMap
            for (ApiDataModel apiDataModel : allApiDataModels) {
                String url = apiDataModel.getUrl();
                if (selectOption.equals("只看status为200") && !apiDataModel.getStatus().contains("200")){
                    continue;
                } else if (selectOption.equals("只看重点") &&  !apiDataModel.getHavingImportant()) {
                    continue;
                } else if (selectOption.equals("只看敏感内容") && !apiDataModel.getResult().contains("敏感内容")){
                    continue;
                } else if (selectOption.equals("只看敏感路径") && !apiDataModel.getResult().contains("敏感路径")) {
                    continue;
                }
                if (url.toLowerCase().contains(searchText.toLowerCase())) {
                    model.insertRow(0, new Object[]{
                            Constants.TREE_STATUS_COLLAPSE,
                            apiDataModel.getId(),
                            apiDataModel.getUrl(),
                            apiDataModel.getPATHNumber(),
                            apiDataModel.getMethod(),
                            apiDataModel.getStatus(),
                            apiDataModel.getIsJsFindUrl(),
                            apiDataModel.getHavingImportant(),
                            apiDataModel.getResult(),
                            apiDataModel.getDescribe(),
                            apiDataModel.getTime()
                    });
                }
            }
        }
    }

    public static void clearAllData(){
        synchronized (model) {
            // 清空model
            model.setRowCount(0);
            // 清空表格
            IProxyScanner.setHaveScanUrlNew();
            // 清空检索
            historySearchText = "";
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    ConfigPanel.searchField.setText("");
                }
            });

            // 还可以清空编辑器中的数据
            MailPanel.requestTextEditor.setMessage(new byte[0], true); // 清空请求编辑器
            MailPanel.responseTextEditor.setMessage(new byte[0], false); // 清空响应编辑器
            MailPanel.resultDeViewer.setText(new byte[0]);
            MailPanel.iHttpService = null; // 清空当前显示的项
            MailPanel.requestsData = null;
            MailPanel.responseData = null;
        }
    }


    public void modelExpand(ApiDataModel apiDataModel, int index) {
        // 关闭自动更新
        ConfigPanel.setFlashButtonFalse();
        operationStartTime = LocalDateTime.now();
        // 看当前是否有过滤场景
        String selectedOption = (String)ConfigPanel.choicesComboBox.getSelectedItem();


        model.setValueAt(Constants.TREE_STATUS_EXPAND, index, 0);
        Map<String, Object> filteredPathData;

        if (selectedOption.equals("只看status为200")) {
            filteredPathData = BurpExtender.getDataBaseService().selectPathDataByUrlAndStatus(apiDataModel.getUrl(), "200");
        } else if (selectedOption.equals("只看重点")) {
            filteredPathData = BurpExtender.getDataBaseService().selectPathDataByUrlAndImportance(apiDataModel.getUrl(), true);
        } else if (selectedOption.equals("只看敏感内容")) {
            filteredPathData = BurpExtender.getDataBaseService().selectPathDataByUrlAndResult(apiDataModel.getUrl(), "敏感内容");
        } else if (selectedOption.equals("只看敏感路径")) {
            filteredPathData = BurpExtender.getDataBaseService().selectPathDataByUrlAndResult(apiDataModel.getUrl(), "敏感路径");
        } else {
            filteredPathData = BurpExtender.getDataBaseService().selectAllPathDataByUrl(apiDataModel.getUrl());
        }

        int tmpIndex = 0;
        for (Map.Entry<String, Object> entry : filteredPathData.entrySet()) {
            tmpIndex += 1;
            String listStatus;
            String path = entry.getKey();
            Map<String, Object> subPathValue = (Map<String, Object>) entry.getValue();

            if (tmpIndex != filteredPathData.size() && filteredPathData.size() != 1) {
                listStatus = "┠";
            } else {
                listStatus = "┗";
            }
            model.insertRow(index + tmpIndex, new Object[]{
                    listStatus,
                    "-",
                    path, // Assuming 'path' is a key in your map
                    "-",
                    subPathValue.get("method"),
                    subPathValue.get("status"),
                    subPathValue.get("isJsFindUrl"),
                    subPathValue.get("isImportant"),
                    subPathValue.get("result"),
                    subPathValue.get("describe"),
                    subPathValue.get("time")
            });
            model.fireTableRowsInserted(index + tmpIndex, index + tmpIndex);
        }

    }

    public void modeCollapse(ApiDataModel apiDataModel, int index) {
        // 看当前是否有过滤场景
        model.setValueAt(Constants.TREE_STATUS_COLLAPSE, index, 0);

        // 计算即将删除的行区间
        int startDeleteIndex = index + 1;
        int deleteNumber = 0;

        // 从后向前删除子项，这样索引就不会因为列表的变动而改变
        int numberOfRows = model.getRowCount();
        for (int i = 0; i < numberOfRows; i++) {
            try {
                if (startDeleteIndex > (model.getRowCount() - 1)){
                    break;
                }
                if (!model.getValueAt(startDeleteIndex, 0).equals(Constants.TREE_STATUS_EXPAND) && !model.getValueAt(startDeleteIndex, 0).equals(Constants.TREE_STATUS_COLLAPSE)) {
                    model.removeRow(startDeleteIndex);
                    deleteNumber += 1;
                } else {
                    break;
                }} catch (Exception e) {
                    BurpExtender.getStderr().println("[!] 数据收起报错，报错如下：");
                    e.printStackTrace(BurpExtender.getStderr());
                }
        }

        // 现在所有的子项都被删除了，通知表格模型更新
        // 注意这里的索引是根据删除前的状态传递的
        model.fireTableRowsDeleted(startDeleteIndex, index+deleteNumber);
    }

    public int findRowIndexByURL(String url) {
        for (int i = 0; i < model.getRowCount(); i++) {
            // 获取每一行第二列的值
            Object value = model.getValueAt(i, 2);
            // 检查这个值是否与要查找的URL匹配
            if (value != null && value.equals(url)) {
                // 如果匹配，返回当前行的索引
                return i;
            }
        }
        // 如果没有找到，返回-1表示未找到
        return -1;
    }

    public String findUrlFromPath(int row){
        for (int index = row; index >= 0; index--) {
            // 获取每一行第二列的值
            String value = (String)model.getValueAt(index, 0);
            if (value.equals(Constants.TREE_STATUS_EXPAND) || value.equals((Constants.TREE_STATUS_COLLAPSE))){
                return (String)model.getValueAt(index, 2);
            }
        }
        return null;
    }

    public DefaultTableModel getModel(){
        return model;
    }

    public ConfigPanel getConfigPanel(){
        return this.configPanel;
    }

}
