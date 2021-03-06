#!/bin/bash

# This script automatically generates the source stencil codes usde as training pattern by Stencil Tune, incuding 2D and 3D kernels with different static parameters (stencil shape, data type, number of buffers).

make
java -cp jar/stenciltune.jar stenciltune.StencilGenerator

echo "Autogenerated stencil codes in /temp/ml_stencil_code"
echo "stencil DSL automatic generation in $SECONDS sec"
