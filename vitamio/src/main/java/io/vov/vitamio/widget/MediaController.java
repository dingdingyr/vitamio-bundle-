/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (C) 2013 YIXIA.COM
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.vov.vitamio.widget;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.vov.vitamio.utils.Log;
import io.vov.vitamio.utils.StringUtils;

/**
 * A view containing controls for a MediaPlayer. Typically contains the buttons
 * like "Play/Pause" and a progress slider. It takes care of synchronizing the
 * controls with the state of the MediaPlayer.
 * <p/>
 * The way to use this class is to a) instantiate it programatically or b)
 * create it in your xml layout.
 * <p/>
 * a) The MediaController will create a default set of controls and put them in
 * a window floating above your application. Specifically, the controls will
 * float above the view specified with setAnchorView(). By default, the window
 * will disappear if left idle for three seconds and reappear when the user
 * touches the anchor view. To customize the MediaController's style, layout and
 * controls you should extend MediaController and override the {#link
 * {@link #makeControllerView()} method.
 * <p/>
 * b) The MediaController is a FrameLayout, you can put it in your layout xml
 * and get it through {@link #findViewById(int)}.
 * <p/>
 * NOTES: In each way, if you want customize the MediaController, the SeekBar's
 * id must be mediacontroller_progress, the Play/Pause's must be
 * mediacontroller_pause, current time's must be mediacontroller_time_current,
 * total time's must be mediacontroller_time_total, file name's must be
 * mediacontroller_file_name. And your resources must have a pause_button
 * drawable and a play_button drawable.
 * <p/>
 * Functions like show() and hide() have no effect when MediaController is
 * created in an xml layout.
 */
public class MediaController extends FrameLayout {
    /**
     * 播放控制面板默认显示时间，超过该时间应该隐藏
     */
    private static final int DEFAULT_TIMEOUT = 2500;

    private static final int MSG_FADE_OUT = 1;
    private static final int MSG_UPDATE_PROGRESS = 2;

    private Context mContext;

    private VideoView mPlayer; // 视频播放器对象

    /**
     * 视频控制面板视图载体
     */
    private PopupWindow mWindow;
    private int mAnimStyle;
    private View mAnchor;
    private View mRoot;

    private OutlineTextView mInfoView;
    /**
     * video name
     */
    private String mTitle;
    /**
     * 视频时长
     */
    private long mVideoDuration;
    /**
     * true, the controller is showing
     */
    private boolean mShowing;
    /**
     * true, user is dragging the playProgressSeekBar
     */
    private boolean mPlayProgressSeekBarDragging;
    private boolean mInstantSeeking = false;

    private ImageButton mPauseButton; //播放/暂停按钮
    private SeekBar mPlayProgressSeekBar; //播放进度条
    private TextView mTotalTimeTv;
    private TextView mCurrentTimeTv;
    private TextView mVideoNameTv;

    private AudioManager mAM;

    private OnShownListener mShownListener;
    private OnHiddenListener mHiddenListener;
    private OnClickListener onBackClickListener;
    private OnClickListener onShareClickListener;

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long pos;
            switch (msg.what) {
                case MSG_FADE_OUT:
                    hide();
                    break;
                case MSG_UPDATE_PROGRESS:
                    pos = updateVideoPlayProgressRelevantUI();
                    if (!mPlayProgressSeekBarDragging && mShowing) {
                        msg = obtainMessage(MSG_UPDATE_PROGRESS);
                        sendMessageDelayed(msg, 1000 - (pos % 1000));
                        updatePausePlayBtn();
                    }
                    break;
            }
        }
    };

    public MediaController(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRoot = this;
        initController(context);
    }

    public MediaController(Context context) {
        super(context);
        if (initController(context))
            initFloatingWindow();
    }

    private boolean initController(Context context) {
        mContext = context;
        mAM = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        return true;
    }

    @Override
    public void onFinishInflate() {
        if (mRoot != null)
            initControllerView(mRoot);

        super.onFinishInflate();
    }

    private void initFloatingWindow() {
        mWindow = new PopupWindow(mContext);
        mWindow.setFocusable(false);
        mWindow.setBackgroundDrawable(null);
        mWindow.setOutsideTouchable(true);
        mAnimStyle = android.R.style.Animation;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public void setWindowLayoutType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            try {
                mAnchor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                Method setWindowLayoutType = PopupWindow.class.getMethod("setWindowLayoutType", new Class[]{int.class});
                setWindowLayoutType.invoke(mWindow, WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
            } catch (Exception e) {
                Log.e("setWindowLayoutType", e);
            }
        }
    }

    /**
     * Set the view that acts as the anchor for the control view. This can for
     * example be a VideoView, or your Activity's main view.
     *
     * @param view The view to which to anchor the controller when it is visible.
     * @note VideoView.setMediaController方法执行时，调用该方法，创建视频播放控制页面
     */
    public void setAnchorView(View view) {
        mAnchor = view;

        removeAllViews();
        mRoot = makeControllerView();
        mWindow.setContentView(mRoot);
        mWindow.setWidth(LayoutParams.MATCH_PARENT);
        mWindow.setHeight(LayoutParams.MATCH_PARENT);

        initControllerView(mRoot);
    }

    /**
     * Create the view that holds the widgets that control playback. Derived
     * classes can override this to create their own.
     *
     * @return The controller view.
     */
    protected View makeControllerView() {
        return ((LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE)).inflate(getResources().getIdentifier("mediacontroller", "layout", mContext.getPackageName()), this);
    }

    Button mBackBtn;
    Button mShareBtn;
    VerticalSeekBar volumeSeekBar;

    private void initControllerView(View v) {
        View rootView = v.findViewById(getResources().getIdentifier("mediacontroller_root_view", "id", mContext.getPackageName()));
        if (rootView != null) {
            rootView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide();
                }
            });
        }

        volumeSeekBar = (VerticalSeekBar) v.findViewById(getResources().getIdentifier("mediacontroller_volume_seekbar", "id", mContext.getPackageName()));
        if (volumeSeekBar != null) {
            volumeSeekBar.setMax(1000);
            volumeSeekBar.setProgress((int) (mVideoVolume * 1000));
            volumeSeekBar.setOnSeekBarChangeListener(mVolumeSeekListener);
        }

        View plusVolume = v.findViewById(getResources().getIdentifier("mediacontroller_volume_plus", "id", mContext.getPackageName()));
        if (plusVolume != null) {
            plusVolume.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mHandler.removeMessages(MSG_FADE_OUT);
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FADE_OUT), DEFAULT_TIMEOUT);

                    if (volumeSeekBar.getProgress() == 1000) {
                        return;
                    }
                    volumeSeekBar.setProgress((volumeSeekBar.getProgress() + 150) % 1000);
                }
            });
        }

        View minusVolume = v.findViewById(getResources().getIdentifier("mediacontroller_volume_minus", "id", mContext.getPackageName()));
        if (minusVolume != null) {
            minusVolume.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mHandler.removeMessages(MSG_FADE_OUT);
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FADE_OUT), DEFAULT_TIMEOUT);

                    if (volumeSeekBar.getProgress() == 0) {
                        return;
                    }
                    volumeSeekBar.setProgress((volumeSeekBar.getProgress() - 150) % 1000);
                }
            });
        }

        mShareBtn = (Button) v.findViewById(getResources().getIdentifier("mediacontroller_share_btn", "id", mContext.getPackageName()));
        if (mShareBtn != null) {
            mShareBtn.setOnClickListener(onShareClickListener);
        }

        mBackBtn = (Button) v.findViewById(getResources().getIdentifier("mediacontroller_back_btn", "id", mContext.getPackageName()));
        if (mBackBtn != null) {
            mBackBtn.setOnClickListener(onBackClickListener);
        }

        mPauseButton = (ImageButton) v.findViewById(getResources().getIdentifier("mediacontroller_play_pause", "id", mContext.getPackageName()));
        if (mPauseButton != null) {
            mPauseButton.requestFocus();
            mPauseButton.setOnClickListener(mPlayPauseListener);
        }

        mPlayProgressSeekBar = (SeekBar) v.findViewById(getResources().getIdentifier("mediacontroller_seekbar", "id", mContext.getPackageName()));
        if (mPlayProgressSeekBar != null) {
            mPlayProgressSeekBar.setOnSeekBarChangeListener(mPlayProgressSeekListener);
            mPlayProgressSeekBar.setMax(1000);
        }

        mTotalTimeTv = (TextView) v.findViewById(getResources().getIdentifier("mediacontroller_time_total", "id", mContext.getPackageName()));
        mCurrentTimeTv = (TextView) v.findViewById(getResources().getIdentifier("mediacontroller_time_current", "id", mContext.getPackageName()));

        mVideoNameTv = (TextView) v.findViewById(getResources().getIdentifier("mediacontroller_file_name", "id", mContext.getPackageName()));
        if (mVideoNameTv != null)
            mVideoNameTv.setText(mTitle);
    }

    public void setMediaPlayer(VideoView player) {
        mPlayer = player;
        updatePausePlayBtn();
    }

    /**
     * Control the action when the seekbar dragged by user
     *
     * @param seekWhenDragging True the media will seek periodically
     */
    public void setInstantSeeking(boolean seekWhenDragging) {
        mInstantSeeking = seekWhenDragging;
    }


    /**
     * Set the content of the file_name TextView
     *
     * @param name
     */
    public void setFileName(String name) {
        mTitle = name;
        if (mVideoNameTv != null)
            mVideoNameTv.setText(mTitle);
    }

    /**
     * Set the View to hold some information when interact with the
     * MediaController
     *
     * @param v
     */
    public void setInfoView(OutlineTextView v) {
        mInfoView = v;
    }

    /**
     * <p>
     * Change the animation style resource for this controller.
     * </p>
     * <p/>
     * <p>
     * If the controller is showing, calling this method will take effect only the
     * next time the controller is shown.
     * </p>
     *
     * @param animationStyle animation style to use when the controller appears
     *                       and disappears. Set to -1 for the default animation, 0 for no animation, or
     *                       a resource identifier for an explicit animation.
     */
    public void setAnimationStyle(int animationStyle) {
        mAnimStyle = animationStyle;
    }


    public void show() {
        show(DEFAULT_TIMEOUT);
    }

    /**
     * Show the controller on screen.
     * It will go away automatically after 'timeout' milliseconds of inactivity.
     *
     * @param timeout The timeout in milliseconds. Use 0 to show the controller
     *                until hide() is called.
     */
    public void show(int timeout) {
        if (!mShowing && mAnchor != null && mAnchor.getWindowToken() != null) {
            if (mPauseButton != null)
                mPauseButton.requestFocus();

            int[] location = new int[2];

            mAnchor.getLocationOnScreen(location);
            Rect anchorRect = new Rect(location[0], location[1], location[0] + mAnchor.getWidth(), location[1] + mAnchor.getHeight());

            mWindow.setAnimationStyle(mAnimStyle);
            setWindowLayoutType();
            mWindow.showAtLocation(mAnchor, Gravity.NO_GRAVITY, anchorRect.left, anchorRect.bottom);

            mShowing = true;

            if (mShownListener != null)
                mShownListener.onShown();
        }
        updatePausePlayBtn();
        mHandler.sendEmptyMessage(MSG_UPDATE_PROGRESS);

        if (timeout != 0) {
            mHandler.removeMessages(MSG_FADE_OUT);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FADE_OUT), timeout);
        }
    }

    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Hide the controller
     */
    public void hide() {
        if (mAnchor == null)
            return;

        if (mShowing) {
            try {
                mHandler.removeMessages(MSG_UPDATE_PROGRESS);
                mWindow.dismiss();

            } catch (IllegalArgumentException ex) {
                Log.d("MediaController already removed");
            }

            mShowing = false;

            if (mHiddenListener != null)
                mHiddenListener.onHidden();
        }
    }

    /**
     * 根据当前视频播放进度，更新播放进度条进度和当前播放时间等UI
     *
     * @return 当前视频播放进度
     */
    private long updateVideoPlayProgressRelevantUI() {
        if (mPlayer == null || mPlayProgressSeekBarDragging)
            return 0;

        long position = mPlayer.getCurrentPosition();
        long duration = mPlayer.getDuration();
        if (mPlayProgressSeekBar != null) {
            if (duration > 0) {
                long pos = 1000L * position / duration;
                mPlayProgressSeekBar.setProgress((int) pos);
            }
            int percent = mPlayer.getBufferPercentage();
            mPlayProgressSeekBar.setSecondaryProgress(percent * 10);
        }

        mVideoDuration = duration;

        if (mTotalTimeTv != null)
            mTotalTimeTv.setText(StringUtils.generateTime(mVideoDuration));
        if (mCurrentTimeTv != null)
            mCurrentTimeTv.setText(StringUtils.generateTime(position));

        return position;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        show(DEFAULT_TIMEOUT);
        return true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        show(DEFAULT_TIMEOUT);
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getRepeatCount() == 0 && (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE)) {
            doPauseResume();
            show(DEFAULT_TIMEOUT);
            if (mPauseButton != null)
                mPauseButton.requestFocus();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (mPlayer.isPlaying()) {
                mPlayer.pause();
                updatePausePlayBtn();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            hide();
            return true;
        } else {
            show(DEFAULT_TIMEOUT);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (mPauseButton != null)
            mPauseButton.setEnabled(enabled);
        if (mPlayProgressSeekBar != null)
            mPlayProgressSeekBar.setEnabled(enabled);
        super.setEnabled(enabled);
    }

    public void setOnShownListener(OnShownListener l) {
        mShownListener = l;
    }

    public void setOnHiddenListener(OnHiddenListener l) {
        mHiddenListener = l;
    }

    public void setOnBackClickListener(OnClickListener listener) {
        onBackClickListener = listener;
        if (mBackBtn != null) {
            mBackBtn.setOnClickListener(onBackClickListener);
        }
    }

    public void setOnShareClickListener(OnClickListener listener) {
        onShareClickListener = listener;
        if (mShareBtn != null) {
            mShareBtn.setOnClickListener(onShareClickListener);
        }
    }

    /**
     * 监听播放/暂停点击事件
     */
    private View.OnClickListener mPlayPauseListener = new View.OnClickListener() {
        public void onClick(View v) {
            doPauseResume();
            show(DEFAULT_TIMEOUT);
        }
    };

    /**
     * 暂停播放或恢复播放
     */
    private void doPauseResume() {
        if (mPlayer.isPlaying())
            mPlayer.pause();
        else
            mPlayer.start();

        updatePausePlayBtn();
    }

    /**
     * 更新播放按钮UI
     */
    private void updatePausePlayBtn() {
        if (mRoot == null || mPauseButton == null)
            return;

        if (mPlayer.isPlaying())
            mPauseButton.setImageResource(getResources().getIdentifier("mediacontroller_pause", "drawable", mContext.getPackageName()));
        else
            mPauseButton.setImageResource(getResources().getIdentifier("mediacontroller_play", "drawable", mContext.getPackageName()));
    }

    private VerticalSeekBar.OnSeekBarChangeListener mVolumeSeekListener = new VerticalSeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(VerticalSeekBar VerticalSeekBar, int progress, boolean fromUser) {
            if (!fromUser)
                return;
            float volume = (float) progress / 1000;
            mPlayer.setVolume(volume, volume);
        }

        @Override
        public void onStartTrackingTouch(VerticalSeekBar VerticalSeekBar) {
            mHandler.removeMessages(MSG_FADE_OUT);
        }

        @Override
        public void onStopTrackingTouch(VerticalSeekBar VerticalSeekBar) {
            mHandler.removeMessages(MSG_FADE_OUT);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_FADE_OUT), DEFAULT_TIMEOUT);
        }
    };

    private OnSeekBarChangeListener mPlayProgressSeekListener = new OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
            mPlayProgressSeekBarDragging = true;
            show(3600000);
            mHandler.removeMessages(MSG_UPDATE_PROGRESS);
            if (mInstantSeeking)
                mAM.setStreamMute(AudioManager.STREAM_MUSIC, true);
            if (mInfoView != null) {
                mInfoView.setText("");
                mInfoView.setVisibility(View.VISIBLE);
            }
        }

        public void onProgressChanged(SeekBar bar, int progress, boolean fromuser) {
            if (!fromuser)
                return;

            long newPosition = (mVideoDuration * progress) / 1000;
            String time = StringUtils.generateTime(newPosition);
            if (mInstantSeeking)
                mPlayer.seekTo(newPosition);
            if (mInfoView != null)
                mInfoView.setText(time);
            if (mCurrentTimeTv != null)
                mCurrentTimeTv.setText(time);
        }

        public void onStopTrackingTouch(SeekBar bar) {
            if (!mInstantSeeking)
                mPlayer.seekTo((mVideoDuration * bar.getProgress()) / 1000);
            if (mInfoView != null) {
                mInfoView.setText("");
                mInfoView.setVisibility(View.GONE);
            }
            show(DEFAULT_TIMEOUT);

            mAM.setStreamMute(AudioManager.STREAM_MUSIC, false);
            mPlayProgressSeekBarDragging = false;

            mHandler.removeMessages(MSG_UPDATE_PROGRESS);
            mHandler.sendEmptyMessageDelayed(MSG_UPDATE_PROGRESS, 1000);
        }
    };


    float mVideoVolume = 0.5f;

    public void setVolume(float videoVolume) {
        mVideoVolume = videoVolume;
    }

    public interface OnShownListener {
        public void onShown();
    }

    public interface OnHiddenListener {
        public void onHidden();
    }

    public interface MediaPlayerControl {
        void start();

        void pause();

        long getDuration();

        long getCurrentPosition();

        void seekTo(long pos);

        boolean isPlaying();

        int getBufferPercentage();
    }
}
