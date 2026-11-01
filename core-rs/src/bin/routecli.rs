use std::{env, fs};

fn main() {
    let a: Vec<String> = env::args().collect();
    let bytes = fs::read(&a[1]).expect("read log");
    println!("{}", droneroute::info_json(bytes.clone()).unwrap_or_else(|e| e));
    if a.get(2).map(|s| s.as_str()) == Some("--req") {
        let log = dji_log_parser::DJILog::from_bytes(bytes).expect("log");
        println!("{}", serde_json::to_string(&log.keychains_request().expect("req")).unwrap());
        return;
    }
    let kc = match a.get(2).map(|s| s.as_str()) {
        Some("--fetch") => {

            let raw = fs::read_to_string(format!("{}/.config/dji/api_key", env::var("HOME").unwrap()))
                .expect("~/.config/dji/api_key");
            let key = raw.lines()
                .find(|l| l.trim_start().starts_with("dji_api_key"))
                .map(|l| l.splitn(2, '=').nth(1).unwrap_or("").trim().trim_matches(|c| c == '\'' || c == '"').to_string())
                .unwrap_or_else(|| raw.trim().to_string());
            let k = droneroute::fetch_keychains_json(bytes.clone(), &key).expect("keychains");
            let kp = format!("{}.keychains.json", a[1]); let kp = env::var("KC_DIR").map(|d| format!("{}/{}", d, std::path::Path::new(&kp).file_name().unwrap().to_string_lossy())).unwrap_or(kp);
            fs::write(&kp, &k).unwrap();
            eprintln!("keychains saved -> {kp}");
            Some(k)
        }
        Some("--json") => None,
        Some(p) => Some(fs::read_to_string(p).unwrap()),
        None => None,
    };
    if let Some(out) = a.iter().position(|x| x == "--json").and_then(|i| a.get(i + 1)) {
        fs::write(out, droneroute::track_json(bytes.clone(), kc.as_deref()).expect("track")).unwrap();
        eprintln!("wrote {out}");
    }
    match droneroute::track(bytes, kc.as_deref()) {
        Ok(t) => {
            eprintln!("points={} home={:?}", t.points.len(), t.home);
            for p in t.points.iter().step_by((t.points.len() / 5).max(1)) {
                eprintln!("  t={:.1}s {:.6},{:.6} h={:.1}m spd={:.1} bat={}", p.t, p.lat, p.lon, p.h, p.spd, p.bat);
            }
        }
        Err(e) => eprintln!("track error: {e}"),
    }
}
