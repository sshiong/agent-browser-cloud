# Recording 投影冲突拒绝与不可变证据核验

> 日期：2026-11-10
> 基线：22cc7d6f51fd3947fb708664e216cda4d8629244

原生产JDBC投影ON CONFLICT DO NOTHING未区分精确重放与冲突Manifest/事件ID。
现在插入未新增行时，核对Recording/Tenant/Session/Node、段/帧/丢帧/遮罩计数、版本、
对象Key、Hash、字节数和起止时间；只有全部一致才允许幂等重放，否则以固定
RECORDING_MANIFEST_IDENTITY_CONFLICT拒绝事件。不会返回私有对象坐标或改写原行。
保存期、Legal Hold等可变治理字段不参与比较；重投可使用不同事件ID但必须对应相同
不可变内容。既有Inbox按事件ID去重的语义未改变，本次不声称检测Inbox已消费后的
同ID异体投递；这是后续独立边界。

真实PG17专用Gate共31项，无失败/错误/跳过：删除14、Hold13、投影4。新增用例
验证同录制不同版本拒绝且原行Hash不变、不同录制占用既有事件ID拒绝且不新增行，
旧v1行Hash不变；原精确重放用例继续通过。Java常规回归通过，专用PG用例在常规
任务中按条件跳过后由此Gate实际运行。首次编译缺少嵌套异常类import，修正后通过。

本轮OrbStack初始停止，启动后重新核对Running/orbstack/OS=OrbStack再执行Gate。
专用随机PG容器由runner退出清理，保留当前其他16个既有容器与共享postgres基础镜像。
没有全局prune。文档与格式检查在提交前执行。

基线主CI42771531383仍运行；Desktop42771531429 Windows仍运行，macOS启动后
取消且未执行步骤，不能算通过。新提交仍须独立CI验证。PG上传准入、默认None、
保全交接/跨Node、真实外部系统/目标云/组织Gate继续保留，目标未全部完成。
