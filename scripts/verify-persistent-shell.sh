#!/system/bin/sh
# 真机自检「持久 shell 会话」（不需要引擎/模型，也不改任何设置）。
#
# 用法：
#   adb push scripts/verify-persistent-shell.sh /data/local/tmp/
#   ND=$(adb shell pm path com.localllm.server | sed 's#package:##; s#/base\.apk##')/lib/arm64
#   adb shell "run-as com.localllm.server sh /data/local/tmp/verify-persistent-shell.sh '$ND'"
#
# 期望输出：第一条命令的哨兵是 `__L2S_END_t1__ 0 /tmp`，第二条只跑 pwd/echo，
# 仍应打印 /tmp 与 X=persist-ok —— 这就证明 cd/export 跨命令保留、且 cat 没吃掉哨兵。
#
# 用 App 完全相同的 proot 参数起一个持久 bash，灌两条命令进去：
# 第一条 cd /tmp + export，第二条只 pwd + echo 变量 —— 验证状态是否跨命令保留。
# 这不是替代 run_bash 的验证，但验证的是真机上最关键的一层：proot + rootfs bash + 哨兵协议 + 管道缓冲。
PKG=com.localllm.server
FILES=/data/user/0/$PKG/files
CACHE=/data/user/0/$PKG/cache
NATIVE=${1:-}
# app uid 看不到 /data/app 的目录列表，所以由外面用 `pm path` 算好传进来；这里兜底试一次
if [ -z "$NATIVE" ]; then
  NATIVE=$(ls -d /data/app/*/$PKG-*/lib/arm64 2>/dev/null | head -1)
fi
echo "NATIVE=$NATIVE"
if [ -z "$NATIVE" ]; then echo "找不到 nativeLibraryDir"; exit 1; fi
if [ ! -x "$FILES/runtime/rootfs/bin/bash" ]; then echo "rootfs 里没有 bash"; exit 1; fi

IN=$FILES/tmp/verify_in.txt
mkdir -p "$FILES/tmp"
# mksh 的 heredoc 要落在 TMPDIR 里，默认 /data/local 对 app uid 不可写
export TMPDIR=$FILES/tmp
cat > "$IN" <<'EOF'
{
cd /tmp && export L2S_X=persist-ok
} </dev/null
printf '__L2S_END_t1__ %s %s\n' "$?" "$PWD"
{
pwd; echo "X=${L2S_X:-MISSING}"; cat; echo "after-cat"
} </dev/null
printf '__L2S_END_t2__ %s %s\n' "$?" "$PWD"
EOF

export PROOT_LOADER=$NATIVE/libproot-loader.so
export PROOT_TMP_DIR=$CACHE/proot-tmp
export PROOT_L2S_DIR=$FILES/runtime/rootfs/root/.l2s
export LD_LIBRARY_PATH=$NATIVE

$NATIVE/libproot.so --kill-on-exit --link2symlink --sysvipc -L -0 \
  --rootfs=$FILES/runtime/rootfs --cwd=/root \
  -b /dev -b /dev/urandom:/dev/random \
  -b /proc -b /proc/self/fd:/dev/fd \
  -b /proc/self/fd/0:/dev/stdin -b /proc/self/fd/1:/dev/stdout -b /proc/self/fd/2:/dev/stderr \
  -b /sys \
  -b $FILES/runtime/rootfs/var/lib/dsh-app/empty:/sys/fs/selinux \
  -b $FILES/runtime/rootfs/var/lib/dsh-app/shm:/dev/shm \
  -b /system -b /apex -b /storage \
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  LANG=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp \
  /bin/bash --noprofile --norc < "$IN"
echo "=== exit=$? ==="
