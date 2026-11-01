package com.zhijian.schedule.core.jw.credential

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 注入教务页的两段 JS：**回填**与**捕获**。
 *
 * ## 为什么不是「读内存里的密码」
 * 安卓上没有任何合法手段从别的进程（WebView 渲染进程 / 系统输入法）里读明文。
 * 所谓「自动读取账号密码」真正可行、也被 Chrome 与各大密码管理器采用的做法是：
 * 在登录页里监听一次表单提交，用户自己按下登录的那一刻把 input 的值取出来。
 * 明文只在用户主动提交的瞬间经过本应用的桥，不经过任何第三方进程。
 *
 * ## 为什么「捕获」与「落库」是两件事
 * 教务登录 POST 失败率不低（验证码错、账号被锁、WAF 拦校外）。提交即落库会把
 * 打错的密码存进去，下一次自动填充就是拿错密码去撞锁。所以 [captureJs] 只把候选值
 * 交给原生侧**暂存在内存里**，等 [com.zhijian.schedule.feature.jw.JwImportViewModel]
 * 看到「已经进入教务系统」才真正写盘——此时这对账号密码已被服务器证明可用。
 *
 * ## 为什么捕获不依赖 sessionStorage
 * 统一认证（sso.jsp / CAS）与教务往往**不是同一个 origin**，页面内的
 * sessionStorage 过不了这道墙。候选值因此在提交瞬间就通过桥交给原生侧，
 * 只存在于内存里，跨 origin 跳转依然成立。
 */
object JwCredentialScripts {
    /** 桥对象名（与 [com.zhijian.schedule.feature.jw.JwCredentialBridge.NAME] 必须一致） */
    const val BRIDGE_OBJECT = "PaperCredentialBridge"

    private val quoteJson = Json { encodeDefaults = true }

    /**
     * 把一个字符串编码成合法的 JS 字符串字面量（含两端引号）。
     *
     * JSON 的字符串语法是 JS 字符串语法的子集：u2028 这类转义在两者里都合法，
     * 所以直接借 JSON 编码即可，不必自己写一遍转义表；顺带让这段逻辑能在纯 JVM 单测里跑。
     */
    internal fun jsLiteral(value: String): String = quoteJson.encodeToString(String.serializer(), value)

    /**
     * 回填：把账号密码写进页面上第一个可见的账号框 / 密码框，并派发 input/change 事件。
     *
     * 为什么不直接 `form.submit()`：教务登录页普遍带验证码，部分学校还用
     * `onclick` + AJAX 校验（强智的登录按钮压根不是 submit）。自动提交会把验证码框
     * 变成一个已经输错过的死状态。这里只填值，用户点一下登录即可。
     *
     * 值一律走 [jsLiteral] 转义：密码里的引号 / 反斜杠 / 换行不能把脚本拼坏。
     * 用 kotlinx 的 JSON 编码而不是 org.json 的 quote——后者在纯 JVM 单测里
     * 是 Android 桩实现（返回 null），会让「转义是否正确」这件事根本测不出来。
     */
    fun fillJs(
        origin: String,
        username: String,
        password: String,
    ): String {
        val originJs = jsLiteral(origin)
        val userJs = jsLiteral(username)
        val passJs = jsLiteral(password)
        return """
            (function () {
              try {
                if (document.body && (document.body.innerText || '').indexOf('安全提示') >= 0) return '';
                var visible = function (el) {
                  if (!el || el.disabled || el.readOnly) return false;
                  var r = el.getBoundingClientRect();
                  if (r.width < 8 || r.height < 8) return false;
                  var s = window.getComputedStyle(el);
                  return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) > 0.1;
                };
                var setValue = function (el, value) {
                  if (!el) return false;
                  var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                  // 走原生 setter：教务页常把 input 的 value 拦成只读属性，
                  // 直接 el.value = x 会被页面 defineProperty 吃掉、也吵不醒框架的监听
                  if (desc && desc.set) desc.set.call(el, value); else el.value = value;
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                  el.dispatchEvent(new Event('change', { bubbles: true }));
                  return true;
                };
                var passInputs = Array.prototype.slice
                  .call(document.querySelectorAll('input[type=password]'))
                  .filter(visible);
                if (!passInputs.length) return '';
                var passInput = passInputs[0];
                var scope = passInput.form || document;
                var userInput =
                  scope.querySelector('input[name*=user i], input[id*=user i], input[name*=account i], input[name*=login i], input[name*=xh i], input[name*=name i]') ||
                  scope.querySelector('input[type=text]') ||
                  scope.querySelector('input:not([type])');
                if (userInput && !visible(userInput)) userInput = null;
                var filledUser = !!userInput && setValue(userInput, $userJs);
                setValue(passInput, $passJs);
                window.__zjFilledOrigin = $originJs;
                return filledUser ? 'user+pass' : 'pass';
              } catch (e) { return ''; }
            })()
            """.trimIndent()
    }

    /**
     * 捕获：登录表单提交（或登录按钮被点、输入框回车）的瞬间，把账号密码交给原生侧暂存。
     *
     * 三件事保证它不吵人：
     * - 只读**可见**的密码框，且密码非空才上报（空提交不产生候选）；
     * - 同一次页面会话里用 `__zjCaptureAt` 节流，连点登录不会刷出一串重复上报；
     * - 装过一次就置 `__zjCaptureInstalled`，页面重入不会叠加监听。
     */
    val captureJs: String =
        """
        (function () {
          try {
            if (window.__zjCaptureInstalled) return;
            window.__zjCaptureInstalled = true;
            var pick = function () {
              var list = document.querySelectorAll('input[type=password]');
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (!el || el.disabled || el.readOnly) continue;
                var r = el.getBoundingClientRect();
                if (r.width < 8 || r.height < 8) continue;
                var s = window.getComputedStyle(el);
                if (s.display === 'none' || s.visibility === 'hidden') continue;
                if (!el.value) continue;
                var scope = el.form || document;
                var userEl =
                  scope.querySelector('input[name*=user i], input[id*=user i], input[name*=account i], input[name*=login i], input[name*=xh i], input[name*=name i]') ||
                  scope.querySelector('input[type=text]') ||
                  scope.querySelector('input:not([type])');
                return { username: userEl ? (userEl.value || '') : '', password: el.value };
              }
              return null;
            };
            var report = function () {
              var now = Date.now();
              // 800ms 节流：submit 与 click 往往同时触发，不该记两条
              if (window.__zjCaptureAt && now - window.__zjCaptureAt < 800) return;
              var c = pick();
              if (!c) return;
              window.__zjCaptureAt = now;
              try {
                $BRIDGE_OBJECT.onCandidate(JSON.stringify({
                  username: c.username,
                  password: c.password,
                  origin: (location.origin || ''),
                  at: now
                }));
              } catch (e) {}
            };
            document.addEventListener('submit', report, true);
            document.addEventListener('click', function (ev) {
              var t = ev.target;
              if (!t) return;
              var tag = (t.tagName || '').toLowerCase();
              if (tag === 'button' || tag === 'input' || tag === 'a') report();
            }, true);
            document.addEventListener('keydown', function (ev) {
              if (ev.key === 'Enter') report();
            }, true);
          } catch (e) {}
        })()
        """.trimIndent()

    /**
     * 页面是否已经「登录成功」了：URL 命中主框架 / 课表页，或页面上已无密码框且
     * 出现教务系统特有的框架结构。命中时回调原生侧的 `onLoggedIn`，由原生决定是否落库。
     *
     * 纯 JS 判定是必要的：登录成功后是**页面自己**跳的，原生侧只能看到 URL 变化；
     * 而部分学校（尤其走 WebVPN 的）跳转后的 URL 特征并不在 [isJwLoggedInPage] 的名单里。
     */
    val loginProbeJs: String =
        """
        (function () {
          try {
            var href = (location.href || '').toLowerCase();
            var passCount = document.querySelectorAll('input[type=password]').length;
            if (passCount > 0) return '';
            var text = ((document.body && document.body.innerText) || '');
            var frames = document.querySelectorAll('frame, iframe').length;
            if (href.indexOf('xsmain') >= 0 || href.indexOf('xskb') >= 0 || href.indexOf('index_initmenu') >= 0) {
              $BRIDGE_OBJECT.onLoggedIn();
              return 'yes';
            }
            if (frames > 0 && text.length > 200) {
              $BRIDGE_OBJECT.onLoggedIn();
              return 'yes';
            }
            return '';
          } catch (e) { return ''; }
        })()
        """.trimIndent()
}
