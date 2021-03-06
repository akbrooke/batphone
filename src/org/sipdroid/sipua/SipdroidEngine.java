/*
 * Copyright (C) 2009 The Sipdroid Open Source Project
 * Copyright (C) 2008 Hughes Systique Corporation, USA (http://www.hsc.com)
 * 
 * This file is part of Sipdroid (http://www.sipdroid.org)
 * 
 * Sipdroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.sipdroid.sipua;

import java.io.IOException;
import org.servalproject.R;
import org.servalproject.ServalBatPhoneApplication;
import org.sipdroid.net.KeepAliveSip;
import org.sipdroid.sipua.ui.LoopAlarm;
import org.sipdroid.sipua.ui.Receiver;
import org.sipdroid.sipua.ui.Settings;
import org.zoolu.net.IpAddress;
import org.zoolu.sip.address.NameAddress;
import org.zoolu.sip.provider.SipProvider;
import org.zoolu.sip.provider.SipStack;

import android.content.Context;
import android.content.SharedPreferences.Editor;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

public class SipdroidEngine implements RegisterAgentListener {

	public static final int LINES = 1;
	public int pref=0;
	
	public static final int UNINITIALIZED = 0x0;
	public static final int INITIALIZED = 0x2;
	
	/** User Agent */
	public UserAgent[] uas;
	public UserAgent ua;

	/** Register Agent */
	private RegisterAgent[] ras;

	private KeepAliveSip[] kas;
	
	/** UserAgentProfile */
	public UserAgentProfile[] user_profiles;

	public SipProvider[] sip_providers;
	
	public static PowerManager.WakeLock[] wl,pwl;
	
	public boolean StartEngine() {
			PowerManager pm = (PowerManager) getUIContext().getSystemService(Context.POWER_SERVICE);
			if (wl == null) {
				wl = new PowerManager.WakeLock[LINES];
				pwl = new PowerManager.WakeLock[LINES];
			}
			uas = new UserAgent[LINES];
			ras = new RegisterAgent[LINES];
			kas = new KeepAliveSip[LINES];
			lastmsgs = new String[LINES];
			sip_providers = new SipProvider[LINES];
			user_profiles = new UserAgentProfile[LINES];
			
			user_profiles[0] = new UserAgentProfile();
			SipStack.init(null);
			int i = 0;
			for (UserAgentProfile user_profile : user_profiles) {
				if (user_profile==null) continue;
				if (wl[i] == null) {
					wl[i] = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sipdroid.SipdroidEngine");
					pwl[i] = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Sipdroid.SipdroidEngine");
				}
				
				try {
					SipStack.max_retransmission_timeout = 4000;
					SipStack.default_transport_protocols = new String[1];
					SipStack.default_transport_protocols[0] = "udp";
					
					String version = "Batphone/" + ServalBatPhoneApplication.version + "/" + Build.MODEL;
					SipStack.ua_info = version;
					SipStack.server_info = version;
					
					sip_providers[i] = new SipProvider(IpAddress.localIpAddress, 0);
					user_profile.contact_url = getContactURL(user_profile.username,sip_providers[i]);
					
					CheckEngine();
					
					// added by mandrajg
					uas[i] = ua = new UserAgent(sip_providers[i], user_profile);
					ras[i] = new RegisterAgent(sip_providers[i], user_profile.from_url, // modified
							user_profile.contact_url, user_profile.username,
							user_profile.realm, user_profile.passwd, this, user_profile,
							user_profile.pub); // added by mandrajg
					kas[i] = new KeepAliveSip(sip_providers[i],100000);
				} catch (Exception E) {
					Log.v("SipDroid","Start Engine failure",E);
				}
				i++;
			}
			register();
			listen();

			return true;
	}

	private String getContactURL(String username,SipProvider sip_provider) {
		int i = username.indexOf("@");
		if (i != -1) {
			// if the username already contains a @ 
			//strip it and everthing following it
			username = username.substring(0, i);
		}

		return username + "@" + IpAddress.localIpAddress
		+ (sip_provider.getPort() != 0?":"+sip_provider.getPort():"")
		+ ";transport=" + sip_provider.getDefaultTransport();		
	}
	
	void setOutboundProxy(SipProvider sip_provider,int i) {
		// PGS 20110323 - Not relevant when talking to SIP via the loopback!
		// (besides, it seems to be the source of ::1 IPv6 address which is preventing SIPDroid from connecting)
	}
	
	public void CheckEngine() {
		int i = 0;
		for (SipProvider sip_provider : sip_providers) {
			if (sip_provider != null && !sip_provider.hasOutboundProxy())
				setOutboundProxy(sip_provider,i);
			i++;
		}
	}

	public Context getUIContext() {
		return Receiver.mContext;
	}
	
	public int getRemoteVideo() {
		return ua.remote_video_port;
	}
	
	public int getLocalVideo() {
		return ua.local_video_port;
	}
	
	public String getRemoteAddr() {
		return ua.remote_media_address;
	}
	
	public void expire() {
		Receiver.expire_time = 0;
		int i = 0;
		for (RegisterAgent ra : ras) {
			if (ra != null && ra.CurrentState == RegisterAgent.REGISTERED) {
				ra.CurrentState = RegisterAgent.UNREGISTERED;
				Receiver.onText(Receiver.REGISTER_NOTIFICATION+i, null, 0, 0);
			}
			i++;
		}
		register();
	}
	
	public void unregister(int i) {
			if (user_profiles[i] == null || user_profiles[i].username.equals("") ||
					user_profiles[i].realm.equals("")) return;

			RegisterAgent ra = ras[i];
			if (ra != null && ra.unregister()) {
				Receiver.alarm(0, LoopAlarm.class);
				Receiver.onText(Receiver.REGISTER_NOTIFICATION+i,getUIContext().getString(R.string.reg),R.drawable.sym_presence_idle,0);
				wl[i].acquire();
			} else
				Receiver.onText(Receiver.REGISTER_NOTIFICATION+i, null, 0, 0);
	}
	
	public void registerMore() {
		int i = 0;
		for (RegisterAgent ra : ras) {
			try {
				if (user_profiles[i] == null || user_profiles[i].username.equals("") ||
						user_profiles[i].realm.equals("")) {
					i++;
					continue;
				}
				user_profiles[i].contact_url = getContactURL(user_profiles[i].from_url,sip_providers[i]);
		
				if (ra != null && !ra.isRegistered() && true && ra.register()) {
					Receiver.onText(Receiver.REGISTER_NOTIFICATION+i,getUIContext().getString(R.string.reg),R.drawable.sym_presence_idle,0);
					wl[i].acquire();
				}
			} catch (Exception ex) {
				Log.v("SipDroid","Failed to register",ex);
			}
			i++;
		}
	}
	
	public void register() {
		int i = 0;
		for (RegisterAgent ra : ras) {
			try {
				if (user_profiles[i] == null || user_profiles[i].username.equals("") ||
						user_profiles[i].realm.equals("")) {
					i++;
					continue;
				}
				user_profiles[i].contact_url = getContactURL(user_profiles[i].from_url,sip_providers[i]);
		
				if (ra != null && ra.register()) {
					Receiver.onText(Receiver.REGISTER_NOTIFICATION+i,getUIContext().getString(R.string.reg),R.drawable.sym_presence_idle,0);
					wl[i].acquire();
				}
			} catch (Exception ex) {
				Log.v("SipDroid","Failed to register",ex);
			}
			i++;
		}
	}
	
	public void registerUdp() {
		int i = 0;
		for (RegisterAgent ra : ras) {
			try {
				if (user_profiles[i] == null || user_profiles[i].username.equals("") ||
						user_profiles[i].realm.equals("") ||
						sip_providers[i] == null ||
						sip_providers[i].getDefaultTransport() == null ||
						sip_providers[i].getDefaultTransport().equals("tcp")) {
					i++;
					continue;
				}
				user_profiles[i].contact_url = getContactURL(user_profiles[i].from_url,sip_providers[i]);
		
				if (ra != null && ra.register()) {
					Receiver.onText(Receiver.REGISTER_NOTIFICATION+i,getUIContext().getString(R.string.reg),R.drawable.sym_presence_idle,0);
					wl[i].acquire();
				}
			} catch (Exception ex) {
				Log.v("SipDroid","Failed to register udp",ex);
			}
			i++;
		}
	}

	public void halt() { // modified
		long time = SystemClock.elapsedRealtime();
		
		int i = 0;
		for (RegisterAgent ra : ras) {
			unregister(i);
			while (ra != null && ra.CurrentState != RegisterAgent.UNREGISTERED && SystemClock.elapsedRealtime()-time < 2000)
				try {
					Thread.sleep(100);
				} catch (InterruptedException e1) {
				}
			if (wl[i].isHeld()) {
				wl[i].release();
				if (pwl[i] != null && pwl[i].isHeld()) pwl[i].release();
			}
			if (kas[i] != null) {
				Receiver.alarm(0, LoopAlarm.class);
				kas[i].halt();
			}
			Receiver.onText(Receiver.REGISTER_NOTIFICATION+i, null, 0, 0);
			if (ra != null)
				ra.halt();
			if (uas[i] != null)
				uas[i].hangup();
			if (sip_providers[i] != null)
				sip_providers[i].halt();
			i++;
		}
	}

	public boolean isRegistered()
	{
		for (RegisterAgent ra : ras)
			if (ra != null && ra.isRegistered())
				return true;
		return false;
	}
	
	boolean isRegistered(int i)
	{
		if (ras[i] == null)
		{
			return false;
		}
		return ras[i].isRegistered();
	}
	
	public void onUaRegistrationSuccess(RegisterAgent reg_ra, NameAddress target,
			NameAddress contact, String result) {
    	int i = 0;
    	for (RegisterAgent ra : ras) {
    		if (ra == reg_ra) break;
    		i++;
    	}
		if (isRegistered(i)) {
			if (Receiver.on_wlan)
				Receiver.alarm(60, LoopAlarm.class);
			Receiver.onText(Receiver.REGISTER_NOTIFICATION+i,getUIContext().getString(R.string.regpref),R.drawable.sym_presence_available,0);
			reg_ra.subattempts = 0;
			reg_ra.startMWI();
		} else
			Receiver.onText(Receiver.REGISTER_NOTIFICATION+i, null, 0,0);
		if (wl[i].isHeld()) {
			wl[i].release();
			if (pwl[i] != null && pwl[i].isHeld()) pwl[i].release();
		}
	}

	String[] lastmsgs;
	
    public void onMWIUpdate(RegisterAgent mwi_ra, boolean voicemail, int number, String vmacc) {
    	int i = 0;
    	for (RegisterAgent ra : ras) {
    		if (ra == mwi_ra) break;
    		i++;
    	}
		if (voicemail) {
			String msgs = getUIContext().getString(R.string.voicemail);
			if (number != 0) {
				msgs = msgs + ": " + number;
			}
			Receiver.MWI_account = vmacc;
			if (lastmsgs[i] == null || !msgs.equals(lastmsgs[i])) {
				Receiver.onText(Receiver.MWI_NOTIFICATION, msgs,android.R.drawable.stat_notify_voicemail,0);
				lastmsgs[i] = msgs;
			}
		} else {
			Receiver.onText(Receiver.MWI_NOTIFICATION, null, 0,0);
			lastmsgs[i] = null;
		}
	}

	static long lasthalt,lastpwl;
	
	/** When a UA failed on (un)registering. */
	public void onUaRegistrationFailure(RegisterAgent reg_ra, NameAddress target,
			NameAddress contact, String result) {
		boolean retry = false;
    	int i = 0;
    	for (RegisterAgent ra : ras) {
    		if (ra == reg_ra) break;
    		i++;
    	}
    	if (isRegistered(i)) {
    		reg_ra.CurrentState = RegisterAgent.UNREGISTERED;
    		Receiver.onText(Receiver.REGISTER_NOTIFICATION+i, null, 0, 0);
    	} else {
    		retry = true;
    		Receiver.onText(Receiver.REGISTER_NOTIFICATION+i,getUIContext().getString(R.string.regfailed)+" ("+result+")",R.drawable.sym_presence_away,0);
    	}
    	if (retry && SystemClock.uptimeMillis() > lastpwl + 45000 && pwl[i] != null && !pwl[i].isHeld() && Receiver.on_wlan) {
			lastpwl = SystemClock.uptimeMillis();
			if (wl[i].isHeld())
				wl[i].release();
			pwl[i].acquire();
			register();
			if (!wl[i].isHeld() && pwl[i].isHeld()) pwl[i].release();
		} else if (wl[i].isHeld()) {
			wl[i].release();
			if (pwl[i] != null && pwl[i].isHeld()) pwl[i].release();
		}
		if (SystemClock.uptimeMillis() > lasthalt + 45000) {
			lasthalt = SystemClock.uptimeMillis();
			sip_providers[i].haltConnections();
		}
		reg_ra.stopMWI();
    	WifiManager wm = (WifiManager) Receiver.mContext.getSystemService(Context.WIFI_SERVICE);
    	wm.startScan();
	}
	
	/** Receives incoming calls (auto accept) */
	public void listen() 
	{
		for (UserAgent ua : uas) {
			if (ua != null) {
				ua.printLog("UAS: WAITING FOR INCOMING CALL");
				
				if (!ua.user_profile.audio && !ua.user_profile.video)
				{
					ua.printLog("ONLY SIGNALING, NO MEDIA");
				}
				
				ua.listen();
			}
		}
	}
	
	public void info(char c, int duration) {
		ua.info(c, duration);
	}
	
	/** Makes a new call */
	public boolean call(String target_url) {
		return ua.call(target_url);
	}

	public void answercall() 
	{
		Receiver.stopRingtone();
		ua.accept();
	}

	public void rejectcall() {
		ua.printLog("UA: HANGUP");
		ua.hangup();
	}

	public void togglehold() {
		ua.reInvite(null, 0);
	}

	public void transfer(String number) {
		ua.callTransfer(number, 0);
	}
	
	public void togglemute() {
		if (ua.muteMediaApplication())
			Receiver.onText(Receiver.CALL_NOTIFICATION, getUIContext().getString(R.string.menu_mute), android.R.drawable.stat_notify_call_mute,Receiver.ccCall.base);
		else
			Receiver.progress();
	}
	
	public void togglebluetooth() {
		ua.bluetoothMediaApplication();
		Receiver.progress();
	}
	
	public int speaker(int mode) {
		int ret = ua.speakerMediaApplication(mode);
		Receiver.progress();
		return ret;
	}
	
	public void keepAlive() {
		int i = 0;
		for (KeepAliveSip ka : kas) {
			if (ka != null && Receiver.on_wlan && isRegistered(i))
				try {
					ka.sendToken();
					Receiver.alarm(60, LoopAlarm.class);
				} catch (IOException e) {
					Log.v("SipDroid","keep alive failed",e);
				}
			i++;
		}
	}

	public static boolean on(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Settings.PREF_ON, Settings.DEFAULT_ON);
	}

	public static void on(Context context,boolean on) {
		Editor edit = PreferenceManager.getDefaultSharedPreferences(context).edit();
		edit.putBoolean(Settings.PREF_ON, on);
		edit.commit();
        if (on) Receiver.engine(context).isRegistered();
	}
}
