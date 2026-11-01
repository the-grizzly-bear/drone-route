use dji_log_parser::keychain::KeychainFeaturePoint;
use dji_log_parser::DJILog;
use serde::Serialize;

pub type Keychains = Vec<Vec<KeychainFeaturePoint>>;

#[derive(Serialize)]
pub struct Info {
    pub version: u8,
    pub needs_keychains: bool,
    pub aircraft: String,
    pub serial: String,
    pub start_time: String,
}

#[derive(Serialize)]
pub struct Pt {
    pub t: f32,
    pub ts: i64,
    pub lat: f64,
    pub lon: f64,
    pub alt: f32,
    pub h: f32,
    pub spd: f32,
    pub yaw: f32,
    pub gp: f32,
    pub gy: f32,
    pub bat: u8,
    pub ph: bool,
    pub vid: bool,
}

#[derive(Serialize)]
pub struct Track {
    pub info: Info,
    pub home: Option<(f64, f64)>,
    pub points: Vec<Pt>,
}

pub fn info(log: &DJILog) -> Info {
    let d = &log.details;
    Info {
        version: log.version,
        needs_keychains: log.version >= 13,
        aircraft: d.aircraft_name.clone(),
        serial: d.aircraft_sn.clone(),
        start_time: d.start_time.to_rfc3339(),
    }
}

pub fn fetch_keychains_json(bytes: Vec<u8>, api_key: &str) -> Result<String, String> {
    let log = DJILog::from_bytes(bytes).map_err(|e| e.to_string())?;
    if log.version < 13 {
        return Ok("[]".into());
    }
    let k = log.fetch_keychains(api_key).map_err(|e| e.to_string())?;
    serde_json::to_string(&k).map_err(|e| e.to_string())
}

pub fn track(bytes: Vec<u8>, keychains_json: Option<&str>) -> Result<Track, String> {
    let log = DJILog::from_bytes(bytes).map_err(|e| e.to_string())?;
    let kc: Option<Keychains> = match keychains_json {
        Some(s) if log.version >= 13 => Some(serde_json::from_str(s).map_err(|e| e.to_string())?),
        _ => None,
    };
    let frames = log.frames(kc).map_err(|e| e.to_string())?;

    let start_ms = log.details.start_time.timestamp_millis();
    let y2000 = 946_684_800_000i64;
    let mut home = None;
    let mut points = Vec::with_capacity(frames.len());
    for (idx, f) in frames.iter().enumerate() {
        if home.is_none() && f.home.latitude != 0.0 && f.home.longitude != 0.0 {
            home = Some((f.home.latitude, f.home.longitude));
        }

        if f.osd.latitude.abs() < 1e-6 && f.osd.longitude.abs() < 1e-6 {
            continue;
        }
        points.push(Pt {
            t: f.osd.fly_time,
            ts: {
                let t = f.custom.date_time.timestamp_millis();
                if t > y2000 { t }
                else if f.osd.fly_time > 0.0 { start_ms + (f.osd.fly_time as f64 * 1000.0) as i64 }
                else { start_ms + idx as i64 * 100 }
            },
            lat: f.osd.latitude,
            lon: f.osd.longitude,
            alt: f.osd.altitude,
            h: f.osd.height,
            spd: (f.osd.x_speed.powi(2) + f.osd.y_speed.powi(2)).sqrt(),
            yaw: f.osd.yaw,
            gp: f.gimbal.pitch,
            gy: f.gimbal.yaw,
            bat: f.battery.charge_level,
            ph: f.camera.is_photo,
            vid: f.camera.is_video,
        });
    }
    Ok(Track { info: info(&log), home, points })
}

pub fn info_json(bytes: Vec<u8>) -> Result<String, String> {
    let log = DJILog::from_bytes(bytes).map_err(|e| e.to_string())?;
    serde_json::to_string(&info(&log)).map_err(|e| e.to_string())
}

pub fn track_json(bytes: Vec<u8>, keychains_json: Option<&str>) -> Result<String, String> {
    serde_json::to_string(&track(bytes, keychains_json)?).map_err(|e| e.to_string())
}

mod jni_api {
    use jni::objects::{JByteArray, JClass, JString};
    use jni::sys::jstring;
    use jni::JNIEnv;

    fn out(env: &mut JNIEnv, r: Result<String, String>) -> jstring {
        let s = r.unwrap_or_else(|e| serde_json::json!({ "error": e }).to_string());
        env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    fn bytes(env: &mut JNIEnv, a: &JByteArray) -> Result<Vec<u8>, String> {
        env.convert_byte_array(a).map_err(|e| e.to_string())
    }

    fn opt_str(env: &mut JNIEnv, s: &JString) -> Option<String> {
        if s.is_null() { return None; }
        env.get_string(s).ok().map(|j| j.into())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_grizzly_droneroute_Native_info<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, log: JByteArray<'l>) -> jstring {
        let r = bytes(&mut env, &log).and_then(super::info_json);
        out(&mut env, r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_grizzly_droneroute_Native_keychains<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, log: JByteArray<'l>, key: JString<'l>) -> jstring {
        let key = opt_str(&mut env, &key).unwrap_or_default();
        let r = bytes(&mut env, &log).and_then(|b| super::fetch_keychains_json(b, &key));
        out(&mut env, r)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_grizzly_droneroute_Native_track<'l>(
        mut env: JNIEnv<'l>, _c: JClass<'l>, log: JByteArray<'l>, kc: JString<'l>) -> jstring {
        let kc = opt_str(&mut env, &kc);
        let r = bytes(&mut env, &log).and_then(|b| super::track_json(b, kc.as_deref()));
        out(&mut env, r)
    }
}
