package com.cubalance.app;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.NfcF;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多卡种通用读卡器。
 *  中国：交通联合 T-Union（A000000632010105，余额 80 5C 00 02 04，单位分）+ 银联电子现金
 *  韩国：KS X 6924 / One Card All Pass（参考开源 Metrodroid, GPLv3）
 *  日本：Suica/PASMO/ICOCA 等 FeliCa 系（NfcF，余额 service 0007）
 *  香港：八达通 Octopus（NfcF，余额 service 0117）
 * 只读不写，不充值。
 */
public class CardReader {

    public static class Result {
        public final TransitCard card;
        public final int balance;
        public final List<Trip> trips;
        public final String diag;
        public Result(TransitCard card, int balance, List<Trip> trips, String diag) {
            this.card = card;
            this.balance = balance;
            this.trips = trips;
            this.diag = diag;
        }
    }

    public static class ReadException extends Exception {
        public final String diag;
        public ReadException(String msg, String diag) {
            super(msg);
            this.diag = diag;
        }
    }

    private static final String CONFIG_DF = "A0000004520001";
    private static final String AID_TUNION = "A000000632010105";   // 交通联合电子钱包
    private static final String AID_PBOC_EP = "A000000333010101";  // 银联电子现金/电子钱包

    // 已知 DF name (AID)。覆盖 KS X 6924 + 交通联合 + 银联
    private static final String[] AID_LIST = {
            "D4100000030001", // T-money / Snapper Plus
            "D4100000140001", // Cashbee / eB / EZL
            AID_TUNION,       // 交通联合（北京/上海/广州/深圳等 300+ 城市）
            "D4100000300001", // MOIBA (Hanpay)
            "D4106509900020", // K-Cash
            "A0000004520001", // One Card All Pass Config DF
            AID_PBOC_EP,      // 银联电子现金
            "D4100000030002", // T-money 变体
            "D4100000150001", // Cashbee 变体
            "A0000004520002"  // OCAP 变体
    };

    private static final StringBuilder DIAG = new StringBuilder();
    private static void log(String s) { DIAG.append(s).append('\n'); }

    /** 一张被识别到的应用 */
    private static class App {
        String aid;
        byte[] fci;
        String name;
        String balHex; // 该应用的余额命令 CLA INS P1 P2
        App(String aid, byte[] fci, String name) {
            this.aid = aid; this.fci = fci; this.name = name;
            this.balHex = balanceCmdForAid(aid);
        }
    }

    public static Result read(Tag tag) throws ReadException {
        DIAG.setLength(0);
        log("tech: " + join(tag.getTechList()));
        byte[] uid = tag.getId();
        log("uid: " + bytesToHex(uid) + " (len " + (uid == null ? 0 : uid.length) + ")");

        // ---- FeliCa 卡（日本/香港）：走 NfcF 通道 ----
        if (hasTech(tag, "android.nfc.tech.NfcF")) {
            return readFelica(tag);
        }

        IsoDep iso = IsoDep.get(tag);
        if (iso == null) {
            throw new ReadException("卡片不支持 IsoDep（非 CPU 交通卡）", DIAG.toString());
        }
        try {
            iso.connect();
            iso.setTimeout(5000);
            log("IsoDep connected, historical=" + bytesToHex(iso.getHistoricalBytes()));

            List<App> apps = new ArrayList<>();
            byte[] configFci = null;

            // ---- 步骤 1：Config DF 动态探测 ----
            byte[] cfgResp = trySelect(iso, CONFIG_DF);
            if (cfgResp != null) {
                configFci = stripSw(cfgResp);
                log("Config DF FCI=" + bytesToHex(configFci));
                byte[] adfAid = findBERTlv(configFci, 0x4F);   // 主应用 AID
                byte[] balCmd = findBERTlv(configFci, 0x11);   // 余额命令
                if (adfAid != null && adfAid.length >= 5) {
                    String aid = bytesToHex(adfAid);
                    log("  ConfigDF -> main AID=" + aid
                            + (balCmd != null ? ", balCmd=" + bytesToHex(balCmd) : ""));
                    // 选中主应用
                    byte[] mainResp = trySelect(iso, aid);
                    byte[] mainFci = mainResp != null ? stripSw(mainResp) : null;
                    apps.add(new App(aid, mainFci, cardNameForAid(aid)));
                }
            } else {
                log("Config DF not present (Cashbee/Snapper/older card)");
            }

            // ---- 步骤 2：遍历所有已知 AID ----
            // Config DF 已识别到主应用时跳过探测（少 6~7 轮通信，显著降低贴卡期间掉卡概率）
            if (apps.isEmpty()) {
                for (String aid : AID_LIST) {
                    if (aid.equals(CONFIG_DF)) continue;
                    byte[] resp;
                    try {
                        resp = trySelect(iso, aid);
                    } catch (IOException e) {
                        // 单个 AID 探测中断不终止整次读取；若已无机会识别则抛出
                        log("AID probe interrupted: " + e.getMessage());
                        throw e;
                    }
                    if (resp != null) {
                        boolean dup = false;
                        for (App a : apps) if (a.aid.equals(aid)) { dup = true; break; }
                        if (!dup) apps.add(new App(aid, stripSw(resp), cardNameForAid(aid)));
                        log("  AID matched: " + aid + " -> " + cardNameForAid(aid));
                    }
                }
            } else {
                log("AID probe skipped (Config DF identified " + apps.get(0).name + ")");
            }
            if (apps.isEmpty()) {
                throw new ReadException("未识别到交通卡应用（AID 均不匹配）", DIAG.toString());
            }

            // ---- 步骤 3：读余额（逐个应用尝试，命令动态优先） ----
            byte[] balanceCmd = null;
            if (configFci != null) {
                byte[] c = findBERTlv(configFci, 0x11);
                if (c != null && c.length >= 4) balanceCmd = c; // CLA INS P1 P2 [Le]
            }
            int balance = -1;
            App usedApp = apps.get(0);
            for (App a : apps) {
                try {
                    trySelect(iso, a.aid); // 切回该应用
                    int b = tryBalance(iso, a, balanceCmd);
                    if (b >= 0) { balance = b; usedApp = a; break; }
                } catch (IOException e) {
                    // 该应用读余额中断：换下一个应用再试，不让单次掉卡直接判死
                    log("balance via " + a.name + " interrupted: " + e.getMessage());
                }
            }
            if (balance < 0) {
                throw new ReadException("余额读取失败（命令不受支持）", DIAG.toString());
            }
            log("BALANCE=" + balance + " via " + usedApp.name);

            // ---- 步骤 4：卡号（多来源） ----
            String csn = "";
            // 4a) Config DF tag 12
            if (configFci != null) {
                byte[] s = findBERTlv(configFci, 0x12);
                if (s != null && s.length >= 4) { csn = bytesToHex(s); log("CSN via ConfigDF tag12=" + csn); }
            }
            // 4b) 应用 FCI tag B0 offset4..11
            if (csn.isEmpty() && usedApp.fci != null) {
                byte[] b0 = findBERTlv(usedApp.fci, 0xB0);
                if (b0 != null && b0.length >= 12) {
                    csn = bytesToHexRange(b0, 4, 8);
                    log("CSN via B0=" + csn);
                }
            }
            // 4b2) 交通联合：FCI tag 9F0C，卡号在 value 偏移 10 起的 10 字节 BCD
            if (csn.isEmpty() && isTUnion(usedApp) && usedApp.fci != null) {
                byte[] v = findBERTlv(usedApp.fci, 0x9F0C);
                if (v != null && v.length >= 20) {
                    csn = bcdDigits(v, 10, 10);
                    log("CSN via 9F0C=" + csn);
                }
            }
            // 4c) FCI tag 84 (DF name 里有时含卡号)
            if (csn.isEmpty() && usedApp.fci != null) {
                byte[] s = findBERTlv(usedApp.fci, 0x84);
                if (s != null && s.length >= 4) { csn = bytesToHex(s); log("CSN via tag84=" + csn); }
            }
            if (csn.isEmpty()) log("CSN not found");

            // ---- 步骤 5：交易记录（尽力而为） ----
            List<Trip> trips = new ArrayList<>();
            try {
                if (isTUnion(usedApp)) {
                    trips = readTripsTUnion(iso, balance);
                } else {
                    trips = readTrips(iso, hexToBytes(usedApp.aid));
                }
            } catch (Exception e) {
                log("trips read failed: " + e.getMessage());
            }

            String key = sha256(uid == null ? new byte[0] : uid);
            String uidTail = tailHex(uid);
            return new Result(new TransitCard(key, uidTail, csn, usedApp.name, 0,
                    currencyForAid(usedApp.aid)), balance, trips, DIAG.toString());
        } catch (IOException e) {
            throw new ReadException("通信失败：" + e.getMessage(), DIAG.toString());
        } catch (ReadException e) {
            throw e;
        } catch (Exception e) {
            throw new ReadException("异常：" + e.getMessage(), DIAG.toString());
        } finally {
            try { iso.close(); } catch (IOException ignored) {}
        }
    }

    /** SELECT by DF name，成功返回原始响应（含SW），失败 null */
    private static byte[] trySelect(IsoDep iso, String aidHex) throws IOException {
        byte[] sel = buildApdu(0x00, 0xA4, 0x04, 0x00, hexToBytes(aidHex), 0x00);
        log(">> SELECT " + aidHex);
        byte[] r = iso.transceive(sel);
        log("   TX " + bytesToHex(sel) + " RX " + bytesToHex(r));
        if (r != null && selectOk(r)) return r;
        return null;
    }

    /** 尝试多种余额命令，返回余额或 -1 */
    private static int tryBalance(IsoDep iso, App app, byte[] dynamicCmd) throws IOException {
        List<byte[]> cmds = new ArrayList<>();
        if (app.balHex != null && app.balHex.length() >= 8) {
            // 该应用自带的余额命令（韩国 90 4C / 交通联合与银联 80 5C）
            byte[] c = hexToBytes(app.balHex);
            cmds.add(buildApdu(c[0] & 0xFF, c[1] & 0xFF, c[2] & 0xFF, c[3] & 0xFF, null, 0x04));
            cmds.add(buildApdu(c[0] & 0xFF, c[1] & 0xFF, c[2] & 0xFF, c[3] & 0xFF, null, 0x00));
        }
        if (dynamicCmd != null && dynamicCmd.length >= 4) {
            // 动态命令：CLA INS P1 P2 [Le]，用卡指定的 Le 或补 04
            if (dynamicCmd.length == 4) {
                cmds.add(buildApdu(dynamicCmd[0] & 0xFF, dynamicCmd[1] & 0xFF,
                        dynamicCmd[2] & 0xFF, dynamicCmd[3] & 0xFF, null, 0x04));
            } else {
                cmds.add(dynamicCmd);
            }
        }
        // 标准命令
        cmds.add(buildApdu(0x90, 0x4C, 0x00, 0x00, null, 0x04));
        cmds.add(buildApdu(0x90, 0x4C, 0x00, 0x00, null, 0x00));
        cmds.add(buildApdu(0x80, 0x4C, 0x00, 0x00, null, 0x04)); // 部分旧卡用 CLA=80

        for (byte[] cmd : cmds) {
            log(">> BAL " + bytesToHex(cmd) + " (" + app.name + ")");
            byte[] r = iso.transceive(cmd);
            log("   RX " + bytesToHex(r));
            if (r != null && balanceOk(r) && r.length >= 6) {
                int v = 0;
                for (int i = 0; i < 4; i++) v = (v << 8) | (r[i] & 0xFF);
                return v;
            }
        }
        return -1;
    }

    /** 读交易记录：用匹配到的 AID 作交易文件路径，SFI 与 PATH 两条路都试 */
    private static List<Trip> readTrips(IsoDep iso, byte[] aid) throws IOException {
        List<Trip> trips = readTripsBySfi(iso);
        log("trips via SFI: " + trips.size());
        if (!trips.isEmpty()) return trips;
        trips = readTripsByPath(iso, aid);
        log("trips via path: " + trips.size());
        return trips;
    }

    private static List<Trip> parseRecords(List<byte[]> records, String tag) {
        List<Trip> trips = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            byte[] data = records.get(i);
            log(tag + " rec" + (i + 1) + " len=" + data.length + " raw=" + bytesToHex(data));
            Trip t = parseTrip(data);
            if (t != null) trips.add(t);
        }
        return trips;
    }

    private static List<Trip> readTripsBySfi(IsoDep iso) throws IOException {
        List<byte[]> records = new ArrayList<>();
        int p2 = (4 << 3) | 4; // 0x24
        for (int rec = 1; rec <= 30; rec++) {
            byte[] cmd = buildApdu(0x00, 0xB2, rec, p2, null, 0x00);
            byte[] r = exchange(iso, "READ-REC-SFI rec=" + rec, cmd);
            if (r == null || !selectOk(r)) break;
            byte[] data = stripSw(r);
            if (data.length < 8) break;
            records.add(data);
        }
        return parseRecords(records, "SFI");
    }

    private static List<Trip> readTripsByPath(IsoDep iso, byte[] aid) throws IOException {
        List<byte[]> records = new ArrayList<>();
        if (aid == null || aid.length < 5) aid = hexToBytes("D4100000030001");
        exchange(iso, "unselect", buildApdu(0x00, 0xA4, 0x00, 0x00, null, 0x00));
        byte[] r1 = exchange(iso, "SELECT txn DF",
                buildApdu(0x00, 0xA4, 0x04, 0x00, aid, 0x00));
        if (r1 == null || !selectOk(r1)) return new ArrayList<>();
        byte[] r2 = exchange(iso, "SELECT txn file 4",
                buildApdu(0x00, 0xA4, 0x00, 0x00, new byte[]{0x00, 0x04}, 0x00));
        if (r2 == null || !selectOk(r2)) return new ArrayList<>();
        for (int rec = 1; rec <= 30; rec++) {
            byte[] r = exchange(iso, "READ-REC-PATH rec=" + rec,
                    buildApdu(0x00, 0xB2, rec, 0x04, null, 0x00));
            if (r == null || !selectOk(r)) break;
            byte[] data = stripSw(r);
            if (data.length < 8) break;
            records.add(data);
        }
        return parseRecords(records, "PATH");
    }

    /** 解析交易记录：支持 T-money 46字节 与 Cashbee 26字节 两种布局 */
    private static Trip parseTrip(byte[] d) {
        if (d == null || d.length < 8) return null;
        int type = d[0] & 0xFF;
        int balance = 0, cost = 0;
        String timeText = "";

        if (d.length >= 33) {
            // T-money 46字节标准布局：balance@2(4), cost@10(4), time@26(7,BCD)
            balance = beInt(d, 2, 4);
            cost = beInt(d, 10, 4);
            long timeRaw = beLong(d, 26, 7);
            timeText = bcdTime(timeRaw);
        } else if (d.length >= 16) {
            // Cashbee 26字节布局（真实卡12条验证）：balance@4(2), cost@10(4)
            balance = beInt(d, 4, 2);
            cost = beInt(d, 10, 4);
            log("  Cashbee-format: type=" + type + " bal=" + balance + " cost=" + cost);
        }
        if (cost == 0 && balance == 0) {
            if (d.length >= 16) {
                Trip t = new Trip();
                t.type = type; t.cost = 0; t.balance = 0; t.timeText = ""; t.timeMs = 0;
                return t;
            }
            return null;
        }
        Trip t = new Trip();
        t.type = type;
        t.cost = Math.abs(cost);
        t.balance = balance;
        t.timeText = timeText;
        t.timeMs = parseBcdToMs(timeText);
        return t;
    }

    /** 7字节BCD时间：YYYYMMDDhhmmss */
    private static String bcdTime(long v) {
        if (v == 0xFFFFFFFFFFFFFFL) return "";
        int yhi = bcd2((int) ((v >> 48) & 0xFF));
        int ylo = bcd2((int) ((v >> 40) & 0xFF));
        int year = yhi * 100 + ylo;
        int month = bcd2((int) ((v >> 32) & 0xFF));
        int day = bcd2((int) ((v >> 24) & 0xFF));
        int hour = bcd2((int) ((v >> 16) & 0xFF));
        int min = bcd2((int) ((v >> 8) & 0xFF));
        int sec = bcd2((int) (v & 0xFF));
        if (month > 12 || day > 31 || hour > 23 || min > 59 || sec > 59) return "";
        return String.format(Locale.US, "%04d%02d%02d%02d%02d%02d",
                year, month, day, hour, min, sec);
    }

    private static long parseBcdToMs(String s) {
        try {
            if (s == null || s.length() != 14) return 0;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setLenient(false);
            return f.parse(s).getTime();
        } catch (Exception e) { return 0; }
    }

    private static int bcd2(int b) { return ((b >> 4) & 0xF) * 10 + (b & 0xF); }

    /** 根据 AID 识别卡片品牌名 */
    private static String cardNameForAid(String aid) {
        if (aid == null) return "交通卡";
        String u = aid.toUpperCase(Locale.US);
        if (u.startsWith("D410000003")) return "T-money";
        if (u.startsWith("D410000014")) return "Cashbee";
        if (u.startsWith("A000000632")) return "交通联合";
        if (u.startsWith("A000000333")) return "银联电子现金";
        if (u.startsWith("D410000030")) return "MOIBA/Hanpay";
        if (u.startsWith("D410650990")) return "K-Cash";
        if (u.startsWith("A000000452")) return "One Card All Pass";
        return "交通卡(" + aid + ")";
    }

    /** 根据 AID 判定币种 */
    private static int currencyForAid(String aid) {
        if (aid == null) return Money.KRW;
        String u = aid.toUpperCase(Locale.US);
        if (u.startsWith("A000000632") || u.startsWith("A000000333")) return Money.CNY;
        return Money.KRW;
    }

    /** 根据 AID 给出该应用的余额命令（CLA INS P1 P2） */
    private static String balanceCmdForAid(String aid) {
        if (aid != null) {
            String u = aid.toUpperCase(Locale.US);
            if (u.startsWith("A000000632") || u.startsWith("A000000333")) return "805C0002";
        }
        return "904C0000";
    }

    private static boolean isTUnion(App a) {
        return a != null && a.aid != null
                && a.aid.toUpperCase(Locale.US).startsWith("A000000632");
    }

    private static boolean hasTech(Tag tag, String name) {
        if (tag == null) return false;
        for (String t : tag.getTechList()) if (t.equals(name)) return true;
        return false;
    }

    /** BCD 字节转数字串 */
    private static String bcdDigits(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            int hi = (b[i] >> 4) & 0xF, lo = b[i] & 0xF;
            if (hi <= 9) sb.append(hi);
            if (lo <= 9) sb.append(lo);
        }
        return sb.length() == 0 ? "" : sb.toString();
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String hex4(int v) {
        return String.format(Locale.US, "%04X", v & 0xFFFF);
    }

    // ==================== FeliCa（日本/香港）====================

    /** FeliCa 读取：Suica 系（service 0007）→ 八达通（service 0117） */
    private static Result readFelica(Tag tag) throws ReadException {
        NfcF felica = NfcF.get(tag);
        if (felica == null) {
            throw new ReadException("卡片不支持 NfcF", DIAG.toString());
        }
        try {
            felica.connect();
            felica.setTimeout(5000);
            byte[] idm = tag.getId();
            if (idm == null || idm.length < 8) {
                throw new ReadException("FeliCa IDm 读取失败", DIAG.toString());
            }
            log("FeliCa IDm=" + bytesToHex(idm));

            // 1) Suica / PASMO / ICOCA / TOICA / nimoca / SUGOCA 等（FeliCa 系统 0003）
            byte[] balBlock = felicaRead(felica, idm, 0x0007, 0);
            if (balBlock != null && balBlock.length >= 12) {
                // 余额在 10-11，2 字节小端（公开布局与 Metrodroid 一致）
                int balance = leInt(balBlock, 10, 2);
                log("Suica-family BALANCE=" + balance);
                List<Trip> trips = new ArrayList<>();
                try { trips = readFelicaTrips(felica, idm, balance); }
                catch (IOException e) { log("felica trips failed: " + e.getMessage()); }
                String key = sha256(idm);
                return new Result(new TransitCard(key, tailHex(idm), bytesToHex(idm),
                        "Suica/PASMO/ICOCA", 0, Money.JPY), balance, trips, DIAG.toString());
            }

            // 2) 八达通 Octopus（系统 8008，余额 service 0117，卡内无历史可读）
            byte[] oct = felicaRead(felica, idm, 0x0117, 0);
            if (oct != null && oct.length >= 4) {
                int raw = beInt(oct, 2, 2);
                // 经验公式（参考 Universal-Card-Reader）：HKD×10 = raw - 350
                int bal10 = raw - 350;
                log("Octopus raw=" + raw + " -> balance=" + bal10 + " (HKD×10)");
                String key = sha256(idm);
                return new Result(new TransitCard(key, tailHex(idm), bytesToHex(idm),
                        "八达通 Octopus", 0, Money.HKD), bal10, new ArrayList<Trip>(), DIAG.toString());
            }

            throw new ReadException("未识别的 FeliCa 卡（非 Suica 系 / 八达通）", DIAG.toString());
        } catch (IOException e) {
            throw new ReadException("通信失败：" + e.getMessage(), DIAG.toString());
        } finally {
            try { felica.close(); } catch (IOException ignored) {}
        }
    }

    /** FeliCa ReadWithoutEncryption（命令 06h / 响应 07h）：读指定 service 的一个 block，返回 16 字节数据，失败 null */
    private static byte[] felicaRead(NfcF f, byte[] idm, int service, int block) throws IOException {
        // 帧：LEN(1) CMD(1) IDm(8) 服务数(1) 服务码LE(2) 块数(1) 块列表(2)
        byte[] req = new byte[16];
        req[0] = 0x10; req[1] = 0x06; // LEN=16, Read Without Encryption
        System.arraycopy(idm, 0, req, 2, 8);
        req[10] = 0x01;
        req[11] = (byte) (service & 0xFF);
        req[12] = (byte) ((service >> 8) & 0xFF);
        req[13] = 0x01;
        req[14] = (byte) 0x80;   // 2 字节块列表元素：高字节 0x80|=服务序号(0)
        req[15] = (byte) block;  // 低字节 = 块号
        byte[] resp = f.transceive(req);
        log(">> FeliCa RW svc=" + hex4(service) + " RX " + bytesToHex(resp));
        if (resp == null || resp.length < 13 + 16) return null;
        if ((resp[1] & 0xFF) != 0x07) return null;          // ReadWithoutEncryptionResponse
        if ((resp[10] & 0xFF) != 0 || (resp[11] & 0xFF) != 0) return null; // status1/2
        byte[] out = new byte[16];
        System.arraycopy(resp, 13, out, 0, 16);
        return out;
    }

    /** Suica 系消费记录：service 090F（循环记录，最新在前），金额由相邻余额差推得 */
    private static List<Trip> readFelicaTrips(NfcF f, byte[] idm, int balanceNow) throws IOException {
        List<Trip> trips = new ArrayList<>();
        int prev = balanceNow;
        for (int start = 0; start < 20; start += 10) {
            int count = Math.min(10, 20 - start);
            // 帧：LEN CMD IDm 服务数 服务码LE 块数 块列表(2×count)
            byte[] req = new byte[14 + 2 * count];
            req[0] = (byte) req.length;
            req[1] = 0x06;
            System.arraycopy(idm, 0, req, 2, 8);
            req[10] = 0x01;
            req[11] = 0x0F; req[12] = 0x09; // service 0x090F LE
            req[13] = (byte) count;
            for (int i = 0; i < count; i++) {
                req[14 + 2 * i] = (byte) 0x80;       // 高字节：2 字节元素 + 服务序号 0
                req[15 + 2 * i] = (byte) (start + i); // 低字节：块号
            }
            byte[] resp = f.transceive(req);
            log(">> FeliCa RW trips start=" + start + " RX len="
                    + (resp == null ? 0 : resp.length));
            if (resp == null || resp.length < 13) break;
            if ((resp[1] & 0xFF) != 0x07 || (resp[10] & 0xFF) != 0 || (resp[11] & 0xFF) != 0) break;
            int n = resp[12] & 0xFF;
            boolean stop = false;
            for (int i = 0; i < n; i++) {
                int off = 13 + i * 16;
                if (off + 16 > resp.length) { stop = true; break; }
                byte[] d = new byte[16];
                System.arraycopy(resp, off, d, 0, 16);
                if (allZero(d)) { stop = true; break; }
                int bal = leInt(d, 10, 2);       // 记录内余额：10-11 小端 2 字节
                int proc = d[1] & 0xFF;
                Trip t = new Trip();
                t.type = (proc == 0x04) ? 2 : 0; // 0x04=チャージ(充值)，其它按消费
                t.cost = Math.abs(prev - bal);   // 记录里不直接存金额，用相邻余额差
                t.balance = bal;
                // 日期 @4-5：16 位打包（7bit 年自 2000 起 / 4bit 月 / 5bit 日）
                int dv = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.clear();
                cal.set(2000 + ((dv >> 9) & 0x7F), ((dv >> 5) & 0xF) - 1, dv & 0x1F);
                t.timeMs = cal.getTimeInMillis();
                t.timeText = "";
                trips.add(t);
                prev = bal;
            }
            if (stop) break;
        }
        log("FeliCa trips: " + trips.size());
        return trips;
    }

    // ==================== 交通联合（中国）====================

    /**
     * T-Union 电子钱包交易明细：SFI 0x18 循环记录，23 字节。
     * 布局：序号@0(2) 预留@2(3) 金额@5(4,BE,分) 类型@9(02充值/06消费/09复合消费)
     *       终端@10(6) 日期@16(4,BCD,YYYYMMDD) 时间@20(3,BCD,HHMMSS)
     * 记录本身不含交易后余额，用当前余额从最新一条往旧倒推。
     */
    private static List<Trip> readTripsTUnion(IsoDep iso, int curBalance) throws IOException {
        List<Trip> trips = new ArrayList<>();
        int p2 = (0x18 << 3) | 4;
        for (int rec = 1; rec <= 20; rec++) {
            byte[] cmd = buildApdu(0x00, 0xB2, rec, p2, null, 0x00);
            byte[] r = exchange(iso, "TUNION-REC " + rec, cmd);
            if (r == null || !selectOk(r)) break;
            byte[] d = stripSw(r);
            if (d.length < 23) break;
            log("  T-Union rec" + rec + " raw=" + bytesToHex(d));
            // 空槽位（循环文件未写入的区域）全为 0：第一条空记录之后都是空的，直接停
            boolean allZero = true;
            for (byte b : d) { if (b != 0) { allZero = false; break; } }
            if (allZero) break;
            int cost = beInt(d, 5, 4);
            int type = d[9] & 0xFF;
            String timeText = bcdDigits(d, 16, 4) + bcdDigits(d, 20, 3);
            Trip t = new Trip();
            t.type = (type == 0x02) ? 2 : 0; // 0x02=充值，0x06/0x09=消费（含复合消费）
            t.cost = cost;
            t.balance = -1; // 先占位，下面统一倒推
            t.timeText = timeText;
            t.timeMs = parseBcdToMs(timeText);
            trips.add(t);
        }
        // 交易后余额倒推：最新一条的余额=当前余额；更旧的 = 更新的余额 - 该笔变动
        // （充值变动为 +金额，消费为 -金额；即往旧走：充值前的余额 = 后余额-充值额，消费前 = 后余额+消费额）
        if (curBalance >= 0 && !trips.isEmpty()) {
            int cur = curBalance;
            for (Trip t : trips) {
                t.balance = cur;
                cur = t.isTopUp() ? cur - t.cost : cur + t.cost;
            }
        }
        log("T-Union trips: " + trips.size());
        return trips;
    }

    private static byte[] stripSw(byte[] r) {
        if (r == null || r.length < 2) return new byte[0];
        byte[] out = new byte[r.length - 2];
        System.arraycopy(r, 0, out, 0, out.length);
        return out;
    }

    private static boolean selectOk(byte[] r) {
        if (r == null || r.length < 2) return false;
        int sw1 = r[r.length - 2] & 0xFF;
        return sw1 == 0x90 || sw1 == 0x61;
    }

    private static boolean balanceOk(byte[] r) {
        if (r == null || r.length < 2) return false;
        int sw1 = r[r.length - 2] & 0xFF;
        int sw2 = r[r.length - 1] & 0xFF;
        return (sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61;
    }

    private static byte[] buildApdu(int cla, int ins, int p1, int p2, byte[] data, int le) {
        if (data == null || data.length == 0) {
            return new byte[]{(byte) cla, (byte) ins, (byte) p1, (byte) p2, (byte) le};
        }
        byte[] out = new byte[6 + data.length];
        out[0] = (byte) cla; out[1] = (byte) ins; out[2] = (byte) p1; out[3] = (byte) p2;
        out[4] = (byte) data.length;
        System.arraycopy(data, 0, out, 5, data.length);
        out[5 + data.length] = (byte) le;
        return out;
    }

    private static byte[] exchange(IsoDep iso, String label, byte[] cmd) throws IOException {
        log(">> " + label + " " + bytesToHex(cmd));
        byte[] r = iso.transceive(cmd);
        log("   RX " + bytesToHex(r));
        return r;
    }

    private static int beInt(byte[] b, int off, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static int leInt(byte[] b, int off, int len) {
        int v = 0;
        for (int i = len - 1; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static long beLong(byte[] b, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    private static String join(String[] a) {
        if (a == null) return "[]";
        StringBuilder sb = new StringBuilder();
        for (String s : a) sb.append(s).append(' ');
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String bytesToHex(byte[] b) { return bytesToHexRange(b, 0, b == null ? 0 : b.length); }

    private static String bytesToHexRange(byte[] b, int off, int len) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len && i < b.length; i++) {
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 递归查找 BER-TLV tag 的 value */
    private static byte[] findBERTlv(byte[] buf, int tag) {
        return findBERTlv(buf, tag, 0);
    }
    private static byte[] findBERTlv(byte[] buf, int tag, int depth) {
        if (buf == null || depth > 4) return null;
        int i = 0;
        while (i + 2 <= buf.length) {
            int t = buf[i] & 0xFF;
            int p = i + 1;
            int tagLen = 1;
            if ((t & 0x1F) == 0x1F) {
                if (p >= buf.length) break;
                tagLen = 2;
                t = (t << 8) | (buf[p] & 0xFF);
                p++;
            }
            if (p >= buf.length) break;
            int len = buf[p] & 0xFF;
            p++;
            if (len == 0x81) {
                if (p >= buf.length) break;
                len = buf[p] & 0xFF; p++;
            } else if (len == 0x82) {
                if (p + 1 >= buf.length) break;
                len = ((buf[p] & 0xFF) << 8) | (buf[p + 1] & 0xFF); p += 2;
            }
            if (len == 0x80 || p + len > buf.length) break;
            if (t == tag) {
                byte[] out = new byte[len];
                System.arraycopy(buf, p, out, 0, len);
                return out;
            }
            if ((t & 0x20) != 0) {
                byte[] sub = new byte[len];
                System.arraycopy(buf, p, sub, 0, len);
                byte[] found = findBERTlv(sub, tag, depth + 1);
                if (found != null) return found;
            }
            i = p + len;
        }
        return null;
    }

    private static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(input));
        } catch (Exception e) { return bytesToHex(input); }
    }

    private static String tailHex(byte[] uid) {
        if (uid == null || uid.length == 0) return "----";
        int n = Math.min(2, uid.length);
        StringBuilder sb = new StringBuilder();
        for (int i = uid.length - n; i < uid.length; i++) {
            sb.append(String.format(Locale.US, "%02X", uid[i] & 0xFF));
        }
        return sb.toString();
    }
}
