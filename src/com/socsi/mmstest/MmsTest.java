package com.socsi.mmstest;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import android.net.http.AndroidHttpClient;

import com.google.android.mms.pdu.CharacterSets;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.PduBody;
import com.google.android.mms.pdu.PduComposer;
import com.google.android.mms.pdu.PduPart;
import com.google.android.mms.pdu.SendReq;

import org.apache.http.params.HttpParams;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.conn.params.ConnRouteParams;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.HttpHost;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;


public class MmsTest extends Activity {
    private static final String TAG = "MmsTest";
    private static Handler myHandler = null;
    private static Context mContext = null;
    // 电信彩信中心url，代理，端口
    public static String mmscUrl_ct = "http://mmsc.vnet.mobi";
    public static String mmsProxy_ct = "10.0.0.200";
    // 移动彩信中心url，代理，端口
    public static String mmscUrl_cm = "http://mmsc.monternet.com";
    public static String mmsProxy_cm = "010.000.000.172";
    // 联通彩信中心url，代理，端口
    public static String mmscUrl_uni = "http://mmsc.vnet.mobi";
    public static String mmsProxy_uni = "10.0.0.172";

    private static String HDR_VALUE_ACCEPT_LANGUAGE = "";
    private static final String HDR_KEY_ACCEPT = "Accept";
    private static final String HDR_KEY_ACCEPT_LANGUAGE = "Accept-Language";
    private static final String HDR_VALUE_ACCEPT = "*/*, application/vnd.wap.mms-message, application/vnd.wap.sic";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        init();
    }

    /**
     * initialize app screen
     */
    private void init() {
        myHandler = new Handler();
        mContext = this;
        initButtons();
    }

    /**
     * framework buttons
     */
    private void initButtons() {
        Button b = (Button) findViewById(R.id.button);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(mContext, "18566239001", "测试彩信", null, null, null);
            }
        });
    }

    private static String getApn(Context context){
        ContentResolver resoler = context.getContentResolver();
        String[] projection = new String[]{"_id"};
        Cursor cur = resoler.query(Uri.parse("content://telephony/carriers/preferapn"),projection, null, null, null);
        String apnId = null;
        if(cur!=null&&cur.moveToFirst()){
            do {
                apnId = cur.getString(cur.getColumnIndex("_id"));
            } while (cur.moveToNext());
        }
        return apnId;
    }
    /**
     * 设置接入点
     * @param id
     */
    private static void setApn(Context context ,String id){
        Uri uri = Uri.parse("content://telephony/carriers/preferapn");
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put("apn_id", id);
        resolver.update(uri, values, null, null);
    }
    private static String APN_NET_ID = null;
    /**
     * 取到wap接入點的id
     * @return
     */
    private static String getWapApnId(Context context){
        ContentResolver contentResolver = context.getContentResolver();
        String[] projection = new String[]{"_id","proxy"};
        Cursor cur = contentResolver.query(Uri.parse("content://telephony/carriers"), projection, "current = 1", null, null);
        if(cur!=null&&cur.moveToFirst()){
            do {
                String id = cur.getString(0);
                String proxy = cur.getString(1);
                if(!TextUtils.isEmpty(proxy)){
                    return id;
                }
            } while (cur.moveToNext());
        }
        return null;
    }

    private static boolean shouldChangeApn(final Context context){

        final String wapId = getWapApnId(context);
        String apnId = getApn(context);
        //若当前apn不是wap，则切换至wap
        if(!wapId.equals(apnId)){
            APN_NET_ID = apnId;
            setApn(context,wapId);
            //切换apn需要一定时间，先让等待2秒
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }

    private static List<String> getSimMNC(Context context){
        TelephonyManager telManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String imsi = telManager.getSubscriberId();
        if(imsi!=null){
            ArrayList<String> list = new ArrayList<String>();
            if(imsi.startsWith("46000") ||imsi.startsWith("46002")){
                //因为移动网络编号46000下的IMSI已经用完，所以虚拟了一个46002编号，134/159号段使用了此编号
                //中国移动
                list.add(mmscUrl_cm);
                list.add(mmsProxy_cm);
            }else if(imsi.startsWith("46001")){
                //中国联通
                list.add(mmscUrl_uni);
                list.add(mmsProxy_uni);
            }else if(imsi.startsWith("46003")){
                //中国电信
                list.add(mmscUrl_ct);
                list.add(mmsProxy_ct);
            }
            shouldChangeApn(context);
            return list;
        }
        return null;
    }

    private static boolean sendMMMS(List<String> list,final Context context, byte[] pdu) throws Exception {
        // HDR_AVLUE_ACCEPT_LANGUAGE = getHttpAcceptLanguage();
        if(list==null){
            myHandler.post(new Runnable() {

                @Override
                public void run() {
                    Toast.makeText(context, "找不到sim卡", Toast.LENGTH_LONG).show();
                }
            });
            return false;
        }
        String mmsUrl = (String) list.get(0);
        String mmsProxy = (String) list.get(1);
        HttpClient client = null;
        try {
            URI hostUrl = new URI(mmsUrl);
            HttpHost target = new HttpHost(hostUrl.getHost(),
                    hostUrl.getPort(), HttpHost.DEFAULT_SCHEME_NAME);
            client = AndroidHttpClient.newInstance("Android-Mms/2.0");
            HttpPost post = new HttpPost(mmsUrl);
            ByteArrayEntity entity = new ByteArrayEntity(pdu);
            entity.setContentType("application/vnd.wap.mms-message");
            post.setEntity(entity);
            post.addHeader(HDR_KEY_ACCEPT, HDR_VALUE_ACCEPT);
            post.addHeader(HDR_KEY_ACCEPT_LANGUAGE, HDR_VALUE_ACCEPT_LANGUAGE);

            HttpParams params = client.getParams();
            HttpProtocolParams.setContentCharset(params, "UTF-8");

            ConnRouteParams.setDefaultProxy(params, new HttpHost(mmsProxy,
                    80));
            HttpResponse response = client.execute(target, post);
            StatusLine status = response.getStatusLine();
            System.out.println("status : " + status.getStatusCode());
            if (status.getStatusCode() != 200) {
                throw new IOException("HTTP error: " + status.getReasonPhrase());
            }
            //彩信发送完毕后检查是否需要把接入点切换回来
            if(null!=APN_NET_ID){
                setApn(context,APN_NET_ID);
            }
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "彩信发送失败："+e.getMessage());
            //发送失败处理
        }
        return false;
    }

    /**
     * 发彩信接口 by liuhanzhi
     * @param context
     * @param phone 手机号
     * @param subject 主题
     * @param text  文字
     * @param imagePath 图片路径
     * @param audioPath 音频路径
     */
    public static void send(final Context context,String phone,String subject,String text,String imagePath,String audioPath) {
//      String subject = "测试彩信";
        Log.v("MmsTestActivity", subject);
//      String recipient = "18911722352";// 138xxxxxxx
        SendReq sendRequest = new SendReq();
        EncodedStringValue[] sub = EncodedStringValue.extract(subject);
        if (sub != null && sub.length > 0) {
            sendRequest.setSubject(sub[0]);
        }
        EncodedStringValue[] phoneNumbers = EncodedStringValue
                .extract(phone);
        if (phoneNumbers != null && phoneNumbers.length > 0) {
            sendRequest.addTo(phoneNumbers[0]);
        }
        PduBody pduBody = new PduBody();
        if(!TextUtils.isEmpty(text)){
            PduPart partPdu3 = new PduPart();
            partPdu3.setCharset(CharacterSets.UTF_8);
            partPdu3.setName("mms_text.txt".getBytes());
            partPdu3.setContentType("text/plain".getBytes());
            partPdu3.setData(text.getBytes());
            pduBody.addPart(partPdu3);
        }
        if(!TextUtils.isEmpty(imagePath)){
            PduPart partPdu = new PduPart();
            partPdu.setCharset(CharacterSets.UTF_8);
            partPdu.setName("camera.jpg".getBytes());
            partPdu.setContentType("image/png".getBytes());
//      partPdu.setDataUri(Uri.parse("file://mnt//sdcard//.lv//photo//1326858009625.jpg"));
            partPdu.setDataUri(Uri.fromFile(new File(imagePath)));
            pduBody.addPart(partPdu);
        }
        if(!TextUtils.isEmpty(audioPath)){
            PduPart partPdu2 = new PduPart();
            partPdu2.setCharset(CharacterSets.UTF_8);
            partPdu2.setName("speech_test.amr".getBytes());
            partPdu2.setContentType("audio/amr".getBytes());
            // partPdu2.setContentType("audio/amr-wb".getBytes());
//          partPdu2.setDataUri(Uri.parse("file://mnt//sdcard//.lv//audio//1326786209801.amr"));
            partPdu2.setDataUri(Uri.fromFile(new File(audioPath)));
            pduBody.addPart(partPdu2);
        }

        sendRequest.setBody(pduBody);
        final PduComposer composer = new PduComposer(context, sendRequest);
        final byte[] bytesToSend = composer.make();
        final List<String> list = getSimMNC(context);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                //因为在切换apn过程中需要一定时间，所以需要加上一个重试操作
                int retry = 0;
                do {
                    Log.d(TAG, "重试次数："+(retry+1));
                    try {
                        if (sendMMMS(list, context, bytesToSend)) {
                            myHandler.post(new Runnable() {

                                @Override
                                public void run() {
                                    Toast.makeText(context, "彩信发送成功！",
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                            return;
                        }
                        retry++;
                        Thread.sleep(2000);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } while (retry < 5);
                myHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(context, "彩信发送失败！", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
        t.start();

    }

}
