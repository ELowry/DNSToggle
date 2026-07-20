package com.ericlowry.dnstoggle.shizuku;

interface IShizukuUserService {
    void destroy() = 16777114;
    boolean grantWriteSecureSettings(String packageName) = 1;
}
