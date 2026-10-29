# 权利人确认 MIT 与 SDK 发布包许可证统一

> 日期：2026-10-29
> 权利人明确答复：MIT 开源；版权人名称 song。

根据本会话权利人两次明确回复，根目录新增MIT LICENSE，署名Copyright (c) 2026 song。
Web、Desktop npm/Cargo、TypeScript/Python/Java SDK元数据统一MIT；Browser Node原有
MIT保留。四语言SDK各含与根目录一致的LICENSE，Go module发布包可直接携带。
Python自定义wheel构建器原先硬编码UNLICENSED，现改为MIT与License-File并放入
.dist-info/LICENSE；Java binary/sources JAR加入META-INF/LICENSE。

发布包验证器要求所有wheel/JAR/zip及TypeScript tgz含精确根LICENSE字节，否则失败。
make build-sdk-release通过，生成8项发布产物；契约/四语言生成Manifest检查均通过，
没有公共API变化（255 Operations / 358 Schemas）。未执行Registry发布。
README中英文增加许可证说明；第三方依赖、资源及deploy/docker/LICENSE.moby保持
各自声明，不把第三方代码改为MIT。原第11项许可证选择与自有元数据冲突已关闭；
其他供应链、第三方许可义务与组织发布Gate仍按既有要求执行。
