package com.zhongyu.ai.view.activity;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import com.example.zhongyu.gobang_ai.R;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jakewharton.rxbinding.view.RxView;
import com.zhongyu.ai.bean.Message;
import com.zhongyu.ai.bean.Point;
import com.zhongyu.ai.event.ConnectEvent;
import com.zhongyu.ai.event.Event;
import com.zhongyu.ai.event.StringEvent;

import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;
import rx.Observer;

import com.zhongyu.ai.rxjava.bluetooth.BluetoothClient;
import com.zhongyu.ai.rxjava.bluetooth.Thread.ConnectThread;
import com.zhongyu.ai.rxjava.bluetooth.Thread.DataTransferThread;
import com.zhongyu.ai.utils.AI;
import com.zhongyu.ai.utils.Config;
import com.zhongyu.ai.utils.Constants;
import com.zhongyu.ai.utils.GameJudger;
import com.zhongyu.ai.utils.GsonUtils;
import com.zhongyu.ai.utils.MessageWrapper;
import com.zhongyu.ai.utils.OperationQueue;
import com.zhongyu.ai.utils.ToastUtil;
import com.zhongyu.ai.view.GoBangBoard;
import com.zhongyu.ai.view.dialog.CompositionDialog;
import com.zhongyu.ai.view.dialog.DialogCenter;

/**
 * Created by zhongyu on 1/12/2018.
 */

public class GameActivity extends AppCompatActivity implements View.OnTouchListener{
    private static final String TAG = "GameActivity";
    private static final int MIN_DELAY_TIME= 1000;  // 两次点击间隔不能少于1000ms
    private static long lastClickTime;
    public static final String GAME_MODE = "gamemode";

    public static final String GAME_WIFI = "gamewifi";
    public static final String GAME_BLUETOOTH = "gamebluetooth";
    public static final String GAME_AI = "gameai";
    public static final String GAME_DOUBLE_AGAIN = "gamedoubleagain";
    private TextView tvComputer;

    private String gameMode = null;

    private GoBangBoard goBangBoard;
    private DialogCenter mDialogCenter;

    private boolean mCanClickConnect = true;//看是否可以点击连接别的蓝牙


    private boolean mIsHost;//主机
    private boolean mIsMePlay = false;
    private boolean mIsGameEnd = false;
    DataTransferThread dataTransferThread;

    private OperationQueue mOperationQueue;

    private AI ai;

    public static void startActivity(Context context, String mode) {
        Intent intent = new Intent(context, GameActivity.class);
        intent.putExtra(GAME_MODE, mode);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game);
        initView();
        initData();
    }

    private void initView() {
        goBangBoard = (GoBangBoard) findViewById(R.id.go_bang_board);
        goBangBoard.setOnTouchListener(this);
        tvComputer = (TextView) findViewById(R.id.tv_com);
        mDialogCenter = new DialogCenter(this);

        //使用Rxjava取代回调
        goBangBoard.putChessSubjuct
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.io())
                .subscribe(new Consumer<GoBangBoard.PutEvent>() {
            @Override
            public void accept(GoBangBoard.PutEvent putEvent) throws Exception {
                onPutChess(putEvent.getmBoard(), putEvent.getX(), putEvent.getY());
            }
        });
    }

    public static boolean isFastClick() {
        boolean flag = true;
        long currentClickTime = System.currentTimeMillis();
        if ((currentClickTime - lastClickTime) >= MIN_DELAY_TIME) {
            flag = false;
        }
        lastClickTime = currentClickTime;
        return flag;
    }

    private void strEventDeal(String s) {
        if(s.endsWith(CompositionDialog.CREAT_GAME)) {
            onCreateGame();
        }else if(s.endsWith(CompositionDialog.JOIN_GAME)) {
            joinGame();
        }else if(s.endsWith(CompositionDialog.BTN_CANCEL)) {
            quitGame();
        }else if(s.endsWith(Constants.WAIT_BEGAN)) {
            if(mIsHost) {
                mDialogCenter.dismissWaitingAndComposition();
                // TODO: 1/22/2018 发送消息到对方
                Message begin = MessageWrapper.getHostBeginMessage();
                sendMessage(begin);
            }
        }
    }


    private void sendMessage(Message message) {
        dataTransferThread.sendData(message);
    }


    /**
     * 棋子放置处理事件
     * @param board 棋盘
     * @param x
     * @param y
     */
    public void onPutChess(int[][] board, int x, int y) {
        if (mIsMePlay && GameJudger.isGameEnd(board, x, y)) {
            ToastUtil.showShort(this, Constants.YOU_WIN);
            Message end = MessageWrapper.getGameEndMessage(Constants.YOU_LOSE);
            sendMessage(end);
            mIsMePlay = false;
            mIsGameEnd = true;
        }
        Point point = new Point();
        point.setXY(x, y);
        mOperationQueue.addOperation(point);
        if(mIsMePlay && gameMode.equals(Constants.AI_MODE)) {
            Point pointAi = ai.search(Config.searchDeep);
            mIsMePlay = false;
            goBangBoard.putChess(false, pointAi.x, pointAi.y);
        }else {
            tvComputer.setVisibility(View.GONE);
            mIsMePlay = true;
        }

    }

    private void connectEventDeal(ConnectEvent connectEvent) {
        if(mCanClickConnect) {
            mCanClickConnect = false;
            if(connectEvent.getmBlueToothDevice() != null) {
                blueConnect(connectEvent.getmBlueToothDevice());
            }else {
                salutConnect();
            }
        }
    }

    private void blueConnect(BluetoothDevice device) {
        ConnectThread connectThread = new ConnectThread(device);
        connectThread.start();
        connectThread.publishClickSubject.subscribe(new Consumer<BluetoothSocket>() {
            @Override
            public void accept(BluetoothSocket bluetoothSocket) throws Exception {
                Toast.makeText(GameActivity.this, "success", Toast.LENGTH_SHORT).show();
                dataTransferThread(bluetoothSocket);
            }
        });
    }

    private void dataTransferThread(BluetoothSocket bluetoothSocket) {
        dataTransferThread = new DataTransferThread(bluetoothSocket);
        dataTransferThread.start();
        dataTransferThread.messaageSubject
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<String>() {
                    @Override
                    public void accept(String s) throws Exception {
                        Message message = GsonUtils.getInstance().fromJson(s, Message.class);
                        messageDeal(message);
                    }
                });
    }

    private void messageDeal(Message message) {
        int type = message.mMessageType;
        switch (type) {
            case Message.MSG_TYPE_HOST_BEGIN:
                mDialogCenter.dismissPeersAndComposition();
                Message ack = MessageWrapper.getHostBeginAckMessage();
                sendMessage(ack);
                ToastUtil.showShort(this, "游戏开始");
                mCanClickConnect = true;
                break;
            case Message.MSG_TYPE_BEGIN_ACK:
                mDialogCenter.dismissWaitingAndComposition();
                mIsMePlay = true;
                break;
            case Message.MSG_TYPE_GAME_DATA:
                goBangBoard.putChess(message.mIsWhite, message.mGameData.x, message.mGameData.y);
                mIsMePlay = true;
                break;
        }
    }

    private void salutConnect() {

    }


    private void onCreateGame() {
        mIsHost = true;
        mDialogCenter.showWaitintPlayerDialog();
        bluetoothScan(true);
    }

    private void joinGame() {
        mIsHost = false;
        mDialogCenter.showPeersDialog();
        bluetoothScan(false);
    }

    private void quitGame() {

    }

    /**
     * 蓝牙
     * @param discoverable ture 扫描或被发现
     */
    private void bluetoothScan(boolean discoverable) {
        BluetoothClient bluetoothClient = BluetoothClient.get(this, discoverable);
        bluetoothClient.publishSubject.subscribe(new Consumer<List<BluetoothDevice>>() {
            @Override
            public void accept(List<BluetoothDevice> bluetoothDeviceList) throws Exception {
                mDialogCenter.updateBlueToothPeers(bluetoothDeviceList, false);
            }
        });
        bluetoothClient.publishClickSubject.observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<BluetoothSocket>() {
            @Override
            public void accept(BluetoothSocket bluetoothSocket) throws Exception {
                onBlueToothDeviceConnected();
                dataTransferThread(bluetoothSocket);
            }
        });
    }

    /**
     * 更新蓝牙扫描
     * @param bluetoothDevice
     */
    private void peerDialogBluUpdate(List<BluetoothDevice> bluetoothDeviceList ,BluetoothDevice bluetoothDevice) {
        bluetoothDeviceList.add(bluetoothDevice);
        mDialogCenter.updateBlueToothPeers(bluetoothDeviceList, true);
    }


    private void initData() {
        gameMode = getIntent().getStringExtra(GAME_MODE);
        if(gameMode.endsWith(Constants.BLUE_TOOTH_MODE)) {
            showCompostionDialog();
        }else if(gameMode.endsWith(Constants.WIFI_MODE)) {
            showCompostionDialog();
        }else if(gameMode.endsWith(Constants.AI_MODE)) {
            mIsHost = true;
        }
        setTitle(gameMode);
        mOperationQueue = new OperationQueue();
    }

    private void showCompostionDialog() {
        mDialogCenter.showCompositionDialog().publishClickSubject.subscribe(new Consumer<Event>() {
            @Override
            public void accept(Event event) throws Exception {
                if(event instanceof StringEvent) {
                    strEventDeal(((StringEvent) event).getStrName());
                }else if(event instanceof ConnectEvent) {
                    connectEventDeal((ConnectEvent) event);
                }
            }
        });
    }


    private void onBlueToothDeviceConnected() {
        ToastUtil.showShort(this, "蓝牙连接成功");
        if(mIsHost) {
            mDialogCenter.enableWaitingPlayerDialogsBegin();
        }
    }



    public void whiteFirst(View view) {

    }

    public void startGame(View view) {
        // TODO: 1/31/2018  AI
        ai = new AI(goBangBoard);
    }

    public void endGame(View view) {

    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if(isFastClick()) {
            return false;
        }

        switch (motionEvent.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!mIsGameEnd && mIsMePlay) {
                    float x = motionEvent.getX();
                    float y = motionEvent.getY();
                    Point point = goBangBoard.convertPoint(x, y);
                    tvComputer.setVisibility(View.VISIBLE);
                    if (goBangBoard.putChess(mIsHost, point.x, point.y)) {
                        if (!gameMode.equals(Constants.AI_MODE)) {
                            Message data = MessageWrapper.getSendDataMessage(point, mIsHost);
                            sendMessage(data);
                            mIsMePlay = false;
                        }
                    }
                }
                break;
        }
        return false;
    }
}
