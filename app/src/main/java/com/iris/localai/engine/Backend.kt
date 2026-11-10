package com.iris.localai.engine

/** 推理后端。这里只做选择与透传，真正的能力由各引擎实现决定。 */
enum class Backend { CPU, GPU, NPU }
