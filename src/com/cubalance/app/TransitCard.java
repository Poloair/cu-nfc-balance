package com.cubalance.app;

/**
 * 一张卡的静态信息 + 识别结果。
 * key 是 sha256(uid) 的主键，卡号 csn 仅本地存储与显示，绝不上传（App 无网络权限）。
 */
public class TransitCard {
    public final String key;        // sha256(uid)，本地主键
    public final String uidTail;    // uid 后四位（十六进制大写）
    public final String csn;        // 卡号（各卡按各自规范解析；空串表示未读到）
    public final String typeName;   // 卡片类型显示名
    public final int typeCode;      // 0=tmoney 1=cashbee 2=moiba 3=kcash 4=ocap 5=unknown
    public final int currency;      // 币种：Money.KRW/CNY/JPY/HKD

    public TransitCard(String key, String uidTail, String csn, String typeName, int typeCode, int currency) {
        this.key = key;
        this.uidTail = uidTail;
        this.csn = csn;
        this.typeName = typeName;
        this.typeCode = typeCode;
        this.currency = currency;
    }
}
