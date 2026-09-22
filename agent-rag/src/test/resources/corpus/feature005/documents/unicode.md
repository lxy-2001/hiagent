# Unicode 码点

Java String.length 返回 UTF-16 码元数。补充平面字符占两个码元；按码点分块应使用 codePointCount 和 offsetByCodePoints，避免切断代理对。
